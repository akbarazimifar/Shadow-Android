package su.sres.securesms.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.core.util.logging.Log;
import su.sres.securesms.notifications.DefaultMessageNotifier;
import su.sres.securesms.notifications.NotificationIds;
import su.sres.securesms.notifications.SingleRecipientNotificationBuilder;
import su.sres.securesms.preferences.widgets.NotificationPrivacyPreference;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.core.util.concurrent.SignalExecutors;

import static su.sres.securesms.util.ConversationUtil.CONVERSATION_SUPPORT_VERSION;

/**
 * Bubble-related utility methods.
 */
public final class BubbleUtil {

    private static final String TAG = Log.tag(BubbleUtil.class);

    private BubbleUtil() {
    }

    /**
     * Checks whether we are allowed to create a bubble for the given recipient.
     *
     * In order to Bubble, a recipient must have a thread, be unblocked, and the user must not have
     * notification privacy settings enabled. Furthermore, we check the Notifications system to verify
     * that bubbles are allowed in the first place.
     */
    @RequiresApi(CONVERSATION_SUPPORT_VERSION)
    @WorkerThread
    public static boolean canBubble(@NonNull Context context, @NonNull RecipientId recipientId, @Nullable Long threadId) {
        if (threadId == null) {
            Log.i(TAG, "Cannot bubble recipient without thread");
            return false;
        }

        NotificationPrivacyPreference privacyPreference = TextSecurePreferences.getNotificationPrivacy(context);
        if (!privacyPreference.isDisplayMessage()) {
            Log.i(TAG, "Bubbles are not available when notification privacy settings are enabled.");
            return false;
        }

        Recipient recipient = Recipient.resolved(recipientId);
        if (recipient.isBlocked()) {
            Log.i(TAG, "Cannot bubble blocked recipient");
            return false;
        }

        NotificationManager notificationManager = ServiceUtil.getNotificationManager(context);
        NotificationChannel conversationChannel = notificationManager.getNotificationChannel(ConversationUtil.getChannelId(context, recipient),
                ConversationUtil.getShortcutId(recipientId));

        return notificationManager.areBubblesAllowed() || (conversationChannel != null && conversationChannel.canBubble());
    }

    /**
     * Display a bubble for a given recipient's thread.
     */
    public static void displayAsBubble(@NonNull Context context, @NonNull RecipientId recipientId, long threadId) {
        if (Build.VERSION.SDK_INT >= CONVERSATION_SUPPORT_VERSION) {
            SignalExecutors.BOUNDED.execute(() -> {
                if (canBubble(context, recipientId, threadId)) {
                    NotificationManager     notificationManager      = ServiceUtil.getNotificationManager(context);
                    StatusBarNotification[] notifications            = notificationManager.getActiveNotifications();
                    int                     threadNotificationId     = NotificationIds.getNotificationIdForThread(threadId);
                    Notification            activeThreadNotification = Stream.of(notifications)
                            .filter(n -> n.getId() == threadNotificationId)
                            .findFirst()
                            .map(StatusBarNotification::getNotification)
                            .orElse(null);

                    if (activeThreadNotification != null && activeThreadNotification.deleteIntent != null) {
                        ApplicationDependencies.getMessageNotifier().updateNotification(context, threadId, BubbleState.SHOWN);
                    } else {
                        Recipient                          recipient = Recipient.resolved(recipientId);
                        SingleRecipientNotificationBuilder builder   = new SingleRecipientNotificationBuilder(context, TextSecurePreferences.getNotificationPrivacy(context));

                        builder.addMessageBody(recipient, recipient, "", System.currentTimeMillis(), null);
                        builder.setThread(recipient);
                        builder.setDefaultBubbleState(BubbleState.SHOWN);
                        builder.setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP);

                        Log.d(TAG, "Posting Notification for requested bubble");
                        notificationManager.notify(NotificationIds.getNotificationIdForThread(threadId), builder.build());
                    }
                }
            });
        }
    }

    public enum BubbleState {
        SHOWN,
        HIDDEN
    }
}
