package su.sres.securesms.conversation;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.ThreadDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.util.BubbleUtil;
import su.sres.securesms.util.ConversationUtil;
import su.sres.core.util.concurrent.SignalExecutors;

import java.util.concurrent.Executor;

class ConversationRepository {

    private final Context  context;
    private final Executor executor;

    ConversationRepository() {
        this.context  = ApplicationDependencies.getApplication();
        this.executor = SignalExecutors.BOUNDED;
    }

    LiveData<ConversationData> getConversationData(long threadId, int jumpToPosition) {
        MutableLiveData<ConversationData> liveData = new MutableLiveData<>();

        executor.execute(() -> {
            liveData.postValue(getConversationDataInternal(threadId, jumpToPosition));
        });

        return liveData;
    }

    @WorkerThread
    boolean canShowAsBubble(long threadId) {
        if (Build.VERSION.SDK_INT >= ConversationUtil.CONVERSATION_SUPPORT_VERSION) {
            Recipient recipient = DatabaseFactory.getThreadDatabase(context).getRecipientForThreadId(threadId);

            return recipient != null && BubbleUtil.canBubble(context, recipient.getId(), threadId);
        } else {
            return false;
        }
    }

    private @NonNull ConversationData getConversationDataInternal(long threadId, int jumpToPosition) {
        ThreadDatabase.ConversationMetadata metadata   = DatabaseFactory.getThreadDatabase(context).getConversationMetadata(threadId);
        int                                 threadSize = DatabaseFactory.getMmsSmsDatabase(context).getConversationCount(threadId);

        long    lastSeen             = metadata.getLastSeen();
        boolean hasSent              = metadata.hasSent();
        int     lastSeenPosition     = 0;
        long    lastScrolled         = metadata.getLastScrolled();
        int     lastScrolledPosition = 0;

        boolean isMessageRequestAccepted = RecipientUtil.isMessageRequestAccepted(context, threadId);

        if (lastSeen > 0) {
            lastSeenPosition = DatabaseFactory.getMmsSmsDatabase(context).getMessagePositionOnOrAfterTimestamp(threadId, lastSeen);
        }

        if (lastSeenPosition <= 0) {
            lastSeen = 0;
        }

        if (lastSeen == 0 && lastScrolled > 0) {
            lastScrolledPosition = DatabaseFactory.getMmsSmsDatabase(context).getMessagePositionOnOrAfterTimestamp(threadId, lastScrolled);
        }

        return new ConversationData(threadId, lastSeen, lastSeenPosition, lastScrolledPosition, hasSent, isMessageRequestAccepted, jumpToPosition, threadSize);
    }
}