package su.sres.securesms.registration.service;

import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import su.sres.securesms.ApplicationContext;
import su.sres.securesms.crypto.IdentityKeyUtil;
import su.sres.securesms.crypto.PreKeyUtil;
import su.sres.securesms.crypto.SessionUtil;
import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.IdentityDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobs.DirectoryRefreshJob;
import su.sres.securesms.jobs.RotateCertificateJob;
import su.sres.securesms.lock.RegistrationLockReminders;
import su.sres.securesms.logging.Log;
import su.sres.securesms.push.AccountManagerFactory;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.service.DirectoryRefreshListener;
import su.sres.securesms.service.RotateSignedPreKeyListener;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.push.exceptions.RateLimitException;
import su.sres.signalservice.internal.push.LockedException;

import java.io.IOException;
import java.util.List;

public final class CodeVerificationRequest {

    private static final String TAG = Log.tag(CodeVerificationRequest.class);

    private enum Result {
        SUCCESS,
        PIN_LOCKED,
        RATE_LIMITED,
        ERROR
    }

    /**
     * Asynchronously verify the account via the code.
     *
     * @param fcmToken The FCM token for the device.
     * @param code     The code that was delivered to the user.
     * @param pin      The users registration pin.
     * @param callback Exactly one method on this callback will be called.
     */
    static void verifyAccount(@NonNull Context context,
                              @NonNull Credentials credentials,
                              @Nullable String fcmToken,
                              @NonNull String code,
                              @Nullable String pin,
                              @NonNull VerifyCallback callback)
    {
        new AsyncTask<Void, Void, Result>() {

            private volatile long timeRemaining;

            @Override
            protected Result doInBackground(Void... voids) {
                try {
                    verifyAccount(context, credentials, code, pin, fcmToken);
                    return Result.SUCCESS;
                } catch (LockedException e) {
                    Log.w(TAG, e);
                    timeRemaining = e.getTimeRemaining();
                    return Result.PIN_LOCKED;
                } catch (RateLimitException e) {
                    Log.w(TAG, e);
                    return Result.RATE_LIMITED;
                } catch (IOException e) {
                    Log.w(TAG, e);
                    return Result.ERROR;
                }
            }

            @Override
            protected void onPostExecute(Result result) {
                if (result == Result.SUCCESS) {

                    handleSuccessfulRegistration(context, pin);

                    callback.onSuccessfulRegistration();
                } else if (result == Result.PIN_LOCKED) {
                    callback.onIncorrectRegistrationLockPin(timeRemaining);
                } else if (result == Result.RATE_LIMITED) {
                    callback.onTooManyAttempts();
                } else if (result == Result.ERROR) {
                    callback.onError();
                }
            }
        }.execute();
    }

    private static void handleSuccessfulRegistration(@NonNull Context context, @Nullable String pin) {
        TextSecurePreferences.setRegistrationLockPin(context, pin);
        TextSecurePreferences.setRegistrationtLockEnabled(context, pin != null);

        if (pin != null) {
            TextSecurePreferences.setRegistrationLockLastReminderTime(context, System.currentTimeMillis());
            TextSecurePreferences.setRegistrationLockNextReminderInterval(context, RegistrationLockReminders.INITIAL_INTERVAL);
        }

        JobManager jobManager = ApplicationDependencies.getJobManager();
        jobManager.add(new DirectoryRefreshJob(false));
        jobManager.add(new RotateCertificateJob(context));

        DirectoryRefreshListener.schedule(context);
        RotateSignedPreKeyListener.schedule(context);
    }

    private static void verifyAccount(@NonNull Context context, @NonNull Credentials credentials, @NonNull String code, @Nullable String pin, @Nullable String fcmToken) throws IOException {
        int     registrationId              = KeyHelper.generateRegistrationId(false);
        byte[]  unidentifiedAccessKey       = UnidentifiedAccessUtil.getSelfUnidentifiedAccessKey(context);
        boolean universalUnidentifiedAccess = TextSecurePreferences.isUniversalUnidentifiedAccess(context);

        TextSecurePreferences.setLocalRegistrationId(context, registrationId);
        SessionUtil.archiveAllSessions(context);

        SignalServiceAccountManager accountManager = AccountManagerFactory.createManager(context, credentials.getE164number(), credentials.getPassword());

        boolean present = fcmToken != null;

        accountManager.verifyAccountWithCode(code, null, registrationId, !present, pin,
                unidentifiedAccessKey, universalUnidentifiedAccess);

        IdentityKeyPair    identityKey  = IdentityKeyUtil.getIdentityKeyPair(context);
        List<PreKeyRecord> records      = PreKeyUtil.generatePreKeys(context);
        SignedPreKeyRecord signedPreKey = PreKeyUtil.generateSignedPreKey(context, identityKey, true);

        accountManager.setPreKeys(identityKey.getPublicKey(), signedPreKey, records);

        if (present) {
            accountManager.setGcmId(Optional.fromNullable(fcmToken));
        }

        TextSecurePreferences.setFcmToken(context, fcmToken);
        TextSecurePreferences.setFcmDisabled(context, !present);
        TextSecurePreferences.setWebsocketRegistered(context, true);
        TextSecurePreferences.setLocalNumber(context, credentials.getE164number());

        DatabaseFactory.getIdentityDatabase(context)
                .saveIdentity(Recipient.self().getId(),
                        identityKey.getPublicKey(), IdentityDatabase.VerifiedStatus.VERIFIED,
                        true, System.currentTimeMillis(), true);

        TextSecurePreferences.setVerifying(context, false);
        TextSecurePreferences.setPushRegistered(context, true);
        TextSecurePreferences.setPushServerPassword(context, credentials.getPassword());
        TextSecurePreferences.setSignedPreKeyRegistered(context, true);
        TextSecurePreferences.setPromptedPushRegistration(context, true);
        TextSecurePreferences.setUnauthorizedReceived(context, false);
        DatabaseFactory.getRecipientDatabase(context).setProfileSharing(Recipient.self().getId(), true);
        DatabaseFactory.getRecipientDatabase(context).setRegistered(Recipient.self().getId(), RecipientDatabase.RegisteredState.REGISTERED);
    }

    public interface VerifyCallback {

        void onSuccessfulRegistration();

        /**
         * @param timeRemaining Time until pin expires and number can be reused.
         */
        void onIncorrectRegistrationLockPin(long timeRemaining);

        void onTooManyAttempts();

        void onError();
    }
}