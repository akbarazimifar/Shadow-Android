package su.sres.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.transport.RetryLaterException;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.crypto.UnidentifiedAccessPair;
import su.sres.signalservice.api.crypto.UntrustedIdentityException;
import su.sres.signalservice.api.messages.SendMessageResult;
import su.sres.signalservice.api.messages.SignalServiceDataMessage;
import su.sres.signalservice.api.messages.SignalServiceGroup;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ProfileKeySendJob extends BaseJob {

    private static final String TAG            = Log.tag(ProfileKeySendJob.class);
    private static final String KEY_RECIPIENTS = "recipients";
    private static final String KEY_THREAD     = "thread";

    public static final String KEY = "ProfileKeySendJob";

    private final long              threadId;
    private final List<RecipientId> recipients;

    /**
     * Suitable for a 1:1 conversation or a GV1 group only.
     */
    @WorkerThread
    public static ProfileKeySendJob create(@NonNull Context context, long threadId) {
        Recipient conversationRecipient = DatabaseFactory.getThreadDatabase(context).getRecipientForThreadId(threadId);

        if (conversationRecipient == null) {
            throw new AssertionError("We have a thread but no recipient!");
        }

        if (conversationRecipient.isPushV2Group()) {
            throw new AssertionError("Do not send profile keys directly for GV2");
        }

        List<RecipientId> recipients = conversationRecipient.isGroup() ? Stream.of(RecipientUtil.getEligibleForSending(conversationRecipient.getParticipants())).map(Recipient::getId).toList()
                : Stream.of(conversationRecipient.getId()).toList();

        recipients.remove(Recipient.self().getId());

        return new ProfileKeySendJob(new Parameters.Builder()
                .setQueue(conversationRecipient.getId().toQueueKey())
                .setLifespan(TimeUnit.DAYS.toMillis(1))
                .setMaxAttempts(Parameters.UNLIMITED)
                .build(), threadId, recipients);
    }

    private ProfileKeySendJob(@NonNull Parameters parameters, long threadId, @NonNull List<RecipientId> recipients) {
        super(parameters);
        this.threadId   = threadId;
        this.recipients = recipients;
    }

    @Override
    protected void onRun() throws Exception {
        Recipient conversationRecipient = DatabaseFactory.getThreadDatabase(context).getRecipientForThreadId(threadId);

        if (conversationRecipient == null) {
            Log.w(TAG, "Thread no longer present");
            return;
        }

        List<Recipient> destinations = Stream.of(recipients).map(Recipient::resolved).toList();
        List<Recipient> completions  = deliver(conversationRecipient, destinations);

        for (Recipient completion : completions) {
            recipients.remove(completion.getId());
        }

        Log.i(TAG, "Completed now: " + completions.size() + ", Remaining: " + recipients.size());

        if (!recipients.isEmpty()) {
            Log.w(TAG, "Still need to send to " + recipients.size() + " recipients. Retrying.");
            throw new RetryLaterException();
        }
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception e) {
        if (e instanceof ServerRejectedException) return false;
        return e instanceof IOException ||
                e instanceof RetryLaterException;
    }

    @Override
    public @NonNull Data serialize() {
        return new Data.Builder()
                .putLong(KEY_THREAD, threadId)
                .putString(KEY_RECIPIENTS, RecipientId.toSerializedList(recipients))
                .build();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onFailure() {

    }

    private List<Recipient> deliver(@NonNull Recipient conversationRecipient, @NonNull List<Recipient> destinations) throws IOException, UntrustedIdentityException {
        SignalServiceMessageSender             messageSender      = ApplicationDependencies.getSignalServiceMessageSender();
        List<SignalServiceAddress>             addresses          = RecipientUtil.toSignalServiceAddressesFromResolved(context, destinations);
        List<Optional<UnidentifiedAccessPair>> unidentifiedAccess = UnidentifiedAccessUtil.getAccessFor(context, destinations);
        SignalServiceDataMessage.Builder       dataMessage        = SignalServiceDataMessage.newBuilder()
                .asProfileKeyUpdate(true)
                .withTimestamp(System.currentTimeMillis())
                .withProfileKey(Recipient.self().resolve().getProfileKey());

        if (conversationRecipient.isGroup()) {
            dataMessage.asGroupMessage(new SignalServiceGroup(conversationRecipient.requireGroupId().getDecodedId()));
        }

        List<SendMessageResult> results = messageSender.sendMessage(addresses, unidentifiedAccess, false, dataMessage.build());

        return GroupSendJobHelper.getCompletedSends(context, results);
    }

    public static class Factory implements Job.Factory<ProfileKeySendJob> {

        @Override
        public @NonNull ProfileKeySendJob create(@NonNull Parameters parameters, @NonNull Data data) {
            long              threadId   = data.getLong(KEY_THREAD);
            List<RecipientId> recipients = RecipientId.fromSerializedList(data.getString(KEY_RECIPIENTS));

            return new ProfileKeySendJob(parameters, threadId, recipients);
        }
    }
}