package su.sres.securesms.jobs;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.signal.libsignal.metadata.InvalidMetadataMessageException;
import org.signal.libsignal.metadata.InvalidMetadataVersionException;
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException;
import org.signal.libsignal.metadata.ProtocolException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidVersionException;
import org.signal.libsignal.metadata.ProtocolLegacyMessageException;
import org.signal.libsignal.metadata.ProtocolNoSessionException;
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException;
import org.signal.libsignal.metadata.SelfSendException;
import su.sres.securesms.MainActivity;
import su.sres.securesms.R;
import su.sres.securesms.crypto.IdentityKeyUtil;
import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.crypto.storage.SignalProtocolStoreImpl;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.NoSuchMessageException;
import su.sres.securesms.database.PushDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.logging.Log;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.transport.RetryLaterException;
import su.sres.securesms.util.GroupUtil;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.crypto.SignalServiceCipher;
import su.sres.signalservice.api.messages.SignalServiceContent;
import su.sres.signalservice.api.messages.SignalServiceEnvelope;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.internal.push.UnsupportedDataMessageException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PushDecryptMessageJob extends BaseJob {

    public static final String KEY   = "PushDecryptJob";
    public static final String QUEUE = "__PUSH_DECRYPT_JOB__";

    public static final String TAG = Log.tag(PushDecryptMessageJob.class);

    private static final String KEY_MESSAGE_ID     = "message_id";
    private static final String KEY_SMS_MESSAGE_ID = "sms_message_id";

    private final long messageId;
    private final long smsMessageId;

    public PushDecryptMessageJob(Context context, long pushMessageId) {
        this(context, pushMessageId, -1);
    }

    public PushDecryptMessageJob(Context context, long pushMessageId, long smsMessageId) {
        this(new Parameters.Builder()
                        .setQueue(QUEUE)
                        .setMaxAttempts(Parameters.UNLIMITED)
                        .build(),
                pushMessageId,
                smsMessageId);
        setContext(context);
    }

    private PushDecryptMessageJob(@NonNull Parameters parameters, long pushMessageId, long smsMessageId) {
        super(parameters);

        this.messageId    = pushMessageId;
        this.smsMessageId = smsMessageId;
    }

    @Override
    public @NonNull Data serialize() {
        return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
                .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
                .build();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onRun() throws NoSuchMessageException, RetryLaterException {
        if (needsMigration()) {
            Log.w(TAG, "Migration is still needed.");
            postMigrationNotification();
            throw new RetryLaterException();
        }

        PushDatabase          database   = DatabaseFactory.getPushDatabase(context);
        SignalServiceEnvelope envelope   = database.get(messageId);
        JobManager            jobManager = ApplicationDependencies.getJobManager();

        try {
            List<Job> jobs = handleMessage(envelope);

            for (Job job: jobs) {
                jobManager.add(job);
            }
        } catch (NoSenderException e) {
            Log.w(TAG, "Invalid message, but no sender info!");
        }

        database.delete(messageId);
    }

    @Override
    public boolean onShouldRetry(@NonNull Exception exception) {
        return exception instanceof RetryLaterException;
    }

    @Override
    public void onCanceled() {
    }

    private boolean needsMigration() {
        return !IdentityKeyUtil.hasIdentityKey(context) || TextSecurePreferences.getNeedsSqlCipherMigration(context);
    }

    private void postMigrationNotification() {
        // TODO [greyson] Navigation
        NotificationManagerCompat.from(context).notify(494949,
                new NotificationCompat.Builder(context, NotificationChannels.getMessagesChannel(context))
                        .setSmallIcon(R.drawable.icon_notification)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setContentTitle(context.getString(R.string.PushDecryptJob_new_locked_message))
                        .setContentText(context.getString(R.string.PushDecryptJob_unlock_to_view_pending_messages))
                        .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), 0))
                        .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                        .build());

    }

    private @NonNull List<Job> handleMessage(@NonNull SignalServiceEnvelope envelope) throws NoSenderException {
        try {
            SignalProtocolStore  axolotlStore = new SignalProtocolStoreImpl(context);
            SignalServiceAddress localAddress = new SignalServiceAddress(Optional.of(TextSecurePreferences.getLocalUuid(context)), Optional.of(TextSecurePreferences.getLocalNumber(context)));
            SignalServiceCipher  cipher       = new SignalServiceCipher(localAddress, axolotlStore, UnidentifiedAccessUtil.getCertificateValidator());

            SignalServiceContent content = cipher.decrypt(envelope);

            List<Job> jobs = new ArrayList<>(2);

            jobs.add(new PushProcessMessageJob(content.serialize(), messageId, smsMessageId, envelope.getTimestamp()));

            if (envelope.isPreKeySignalMessage()) {
                jobs.add(new RefreshPreKeysJob());
            }

            return jobs;

        } catch (ProtocolInvalidVersionException e) {
            Log.w(TAG, e);
            return Collections.singletonList(new PushProcessMessageJob(PushProcessMessageJob.MessageState.INVALID_VERSION,
                    toExceptionMetadata(e),
                    messageId,
                    smsMessageId,
                    envelope.getTimestamp()));

        } catch (ProtocolInvalidMessageException | ProtocolInvalidKeyIdException | ProtocolInvalidKeyException | ProtocolUntrustedIdentityException e) {
            Log.w(TAG, e);
            return Collections.singletonList(new PushProcessMessageJob(PushProcessMessageJob.MessageState.CORRUPT_MESSAGE,
                    toExceptionMetadata(e),
                    messageId,
                    smsMessageId,
                    envelope.getTimestamp()));

        } catch (ProtocolNoSessionException e) {
            Log.w(TAG, e);
            return Collections.singletonList(new PushProcessMessageJob(PushProcessMessageJob.MessageState.NO_SESSION,
                    toExceptionMetadata(e),
                    messageId,
                    smsMessageId,
                    envelope.getTimestamp()));

        } catch (ProtocolLegacyMessageException e) {
            Log.w(TAG, e);
            return Collections.singletonList(new PushProcessMessageJob(PushProcessMessageJob.MessageState.LEGACY_MESSAGE,
                    toExceptionMetadata(e),
                    messageId,
                    smsMessageId,
                    envelope.getTimestamp()));

        } catch (ProtocolDuplicateMessageException e) {
            Log.w(TAG, e);
            return Collections.singletonList(new PushProcessMessageJob(PushProcessMessageJob.MessageState.DUPLICATE_MESSAGE,
                    toExceptionMetadata(e),
                    messageId,
                    smsMessageId,
                    envelope.getTimestamp()));

        } catch (InvalidMetadataVersionException | InvalidMetadataMessageException e) {
            Log.w(TAG, e);
            return Collections.emptyList();

        } catch (SelfSendException e) {
            Log.i(TAG, "Dropping UD message from self.");
            return Collections.emptyList();

        } catch (UnsupportedDataMessageException e) {
            Log.w(TAG, e);
            return Collections.singletonList(new PushProcessMessageJob(PushProcessMessageJob.MessageState.UNSUPPORTED_DATA_MESSAGE,
                    toExceptionMetadata(e),
                    messageId,
                    smsMessageId,
                    envelope.getTimestamp()));
        }
    }

    private static PushProcessMessageJob.ExceptionMetadata toExceptionMetadata(@NonNull UnsupportedDataMessageException e) throws NoSenderException {
        String sender = e.getSender();

        if (sender == null) throw new NoSenderException();

        return new PushProcessMessageJob.ExceptionMetadata(sender,
                e.getSenderDevice(),
                e.getGroup().transform(g -> GroupUtil.getEncodedId(g.getGroupId(), false)).orNull());
    }

    private static PushProcessMessageJob.ExceptionMetadata toExceptionMetadata(@NonNull ProtocolException e) throws NoSenderException {
        String sender = e.getSender();

        if (sender == null) throw new NoSenderException();

        return new PushProcessMessageJob.ExceptionMetadata(sender, e.getSenderDevice());
    }

    public static final class Factory implements Job.Factory<PushDecryptMessageJob> {
        @Override
        public @NonNull PushDecryptMessageJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new PushDecryptMessageJob(parameters, data.getLong(KEY_MESSAGE_ID), data.getLong(KEY_SMS_MESSAGE_ID));
        }
    }

    private static class NoSenderException extends Exception {}
}