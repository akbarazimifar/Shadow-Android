package su.sres.securesms.conversation.ui.error;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.annimon.stream.Stream;

import su.sres.securesms.crypto.DatabaseSessionLock;
import su.sres.securesms.crypto.storage.TextSecureIdentityKeyStore;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.IdentityDatabase;
import su.sres.securesms.database.IdentityDatabase.IdentityRecord;
import su.sres.securesms.database.MessageDatabase;
import su.sres.securesms.database.MmsSmsDatabase;
import su.sres.securesms.database.NoSuchMessageException;
import su.sres.securesms.database.model.MessageRecord;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.sms.MessageSender;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.signalservice.api.SignalSessionLock;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.SignalProtocolAddress;

import java.util.Collection;
import java.util.List;

final class SafetyNumberChangeRepository {

    private static final String TAG = SafetyNumberChangeRepository.class.getSimpleName();

    private final Context context;

    SafetyNumberChangeRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull LiveData<TrustAndVerifyResult> trustOrVerifyChangedRecipients(@NonNull List<ChangedRecipient> changedRecipients) {
        MutableLiveData<TrustAndVerifyResult> liveData = new MutableLiveData<>();
        SignalExecutors.BOUNDED.execute(() -> liveData.postValue(trustOrVerifyChangedRecipientsInternal(changedRecipients)));
        return liveData;
    }

    @NonNull LiveData<TrustAndVerifyResult> trustOrVerifyChangedRecipientsAndResend(@NonNull List<ChangedRecipient> changedRecipients, @NonNull MessageRecord messageRecord) {
        MutableLiveData<TrustAndVerifyResult> liveData = new MutableLiveData<>();
        SignalExecutors.BOUNDED.execute(() -> liveData.postValue(trustOrVerifyChangedRecipientsAndResendInternal(changedRecipients, messageRecord)));
        return liveData;
    }

    @WorkerThread
    public @NonNull SafetyNumberChangeState getSafetyNumberChangeState(@NonNull Collection<RecipientId> recipientIds, @Nullable Long messageId, @Nullable String messageType) {
        MessageRecord messageRecord = null;
        if (messageId != null && messageType != null) {
            messageRecord = getMessageRecord(messageId, messageType);
        }

        List<Recipient> recipients = Stream.of(recipientIds).map(Recipient::resolved).toList();

        List<ChangedRecipient> changedRecipients = Stream.of(DatabaseFactory.getIdentityDatabase(context).getIdentities(recipients).getIdentityRecords())
                .map(record -> new ChangedRecipient(Recipient.resolved(record.getRecipientId()), record))
                .toList();

        return new SafetyNumberChangeState(changedRecipients, messageRecord);
    }

    @WorkerThread
    private @Nullable MessageRecord getMessageRecord(Long messageId, String messageType) {
        try {
            switch (messageType) {
                case MmsSmsDatabase.SMS_TRANSPORT:
                    return DatabaseFactory.getSmsDatabase(context).getMessageRecord(messageId);
                case MmsSmsDatabase.MMS_TRANSPORT:
                    return DatabaseFactory.getMmsDatabase(context).getMessageRecord(messageId);
                default:
                    throw new AssertionError("no valid message type specified");
            }
        } catch (NoSuchMessageException e) {
            Log.i(TAG, e);
        }
        return null;
    }

    @WorkerThread
    private TrustAndVerifyResult trustOrVerifyChangedRecipientsInternal(@NonNull List<ChangedRecipient> changedRecipients) {
        IdentityDatabase identityDatabase = DatabaseFactory.getIdentityDatabase(context);

        try(SignalSessionLock.Lock unused = DatabaseSessionLock.INSTANCE.acquire()) {
            for (ChangedRecipient changedRecipient : changedRecipients) {
                IdentityRecord identityRecord = changedRecipient.getIdentityRecord();

                if (changedRecipient.isUnverified()) {
                    identityDatabase.setVerified(identityRecord.getRecipientId(),
                            identityRecord.getIdentityKey(),
                            IdentityDatabase.VerifiedStatus.DEFAULT);
                } else {
                    identityDatabase.setApproval(identityRecord.getRecipientId(), true);
                }
            }
        }

        return TrustAndVerifyResult.trustAndVerify(changedRecipients);
    }

    @WorkerThread
    private TrustAndVerifyResult trustOrVerifyChangedRecipientsAndResendInternal(@NonNull List<ChangedRecipient> changedRecipients,
                                                                                 @NonNull MessageRecord messageRecord) {
        try(SignalSessionLock.Lock unused = DatabaseSessionLock.INSTANCE.acquire()) {
            for (ChangedRecipient changedRecipient : changedRecipients) {
                SignalProtocolAddress      mismatchAddress  = new SignalProtocolAddress(changedRecipient.getRecipient().requireServiceId(), 1);
                TextSecureIdentityKeyStore identityKeyStore = new TextSecureIdentityKeyStore(context);
                identityKeyStore.saveIdentity(mismatchAddress, changedRecipient.getIdentityRecord().getIdentityKey(), true);
            }
        }

        if (messageRecord.isOutgoing()) {
            processOutgoingMessageRecord(changedRecipients, messageRecord);
        }

        return TrustAndVerifyResult.trustVerifyAndResend(changedRecipients, messageRecord);
    }

    @WorkerThread
    private void processOutgoingMessageRecord(@NonNull List<ChangedRecipient> changedRecipients, @NonNull MessageRecord messageRecord) {
        MessageDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);
        MessageDatabase mmsDatabase = DatabaseFactory.getMmsDatabase(context);

        for (ChangedRecipient changedRecipient : changedRecipients) {
            RecipientId id          = changedRecipient.getRecipient().getId();
            IdentityKey identityKey = changedRecipient.getIdentityRecord().getIdentityKey();

            if (messageRecord.isMms()) {
                mmsDatabase.removeMismatchedIdentity(messageRecord.getId(), id, identityKey);

                if (messageRecord.getRecipient().isPushGroup()) {
                    MessageSender.resendGroupMessage(context, messageRecord, id);
                } else {
                    MessageSender.resend(context, messageRecord);
                }
            } else {
                smsDatabase.removeMismatchedIdentity(messageRecord.getId(), id, identityKey);

                MessageSender.resend(context, messageRecord);
            }
        }
    }

    static final class SafetyNumberChangeState {

        private final List<ChangedRecipient> changedRecipients;
        private final MessageRecord          messageRecord;

        SafetyNumberChangeState(List<ChangedRecipient> changedRecipients, @Nullable MessageRecord messageRecord) {
            this.changedRecipients = changedRecipients;
            this.messageRecord     = messageRecord;
        }

        @NonNull List<ChangedRecipient> getChangedRecipients() {
            return changedRecipients;
        }

        @Nullable MessageRecord getMessageRecord() {
            return messageRecord;
        }
    }
}