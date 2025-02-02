package su.sres.securesms.recipients.ui.notifications;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.core.util.concurrent.SignalExecutors;

class CustomNotificationsRepository {

    private final Context     context;
    private final RecipientId recipientId;

    CustomNotificationsRepository(@NonNull Context context, @NonNull RecipientId recipientId) {
        this.context     = context;
        this.recipientId = recipientId;
    }

    void onLoad(@NonNull Runnable onLoaded) {
        SignalExecutors.SERIAL.execute(() -> {
            Recipient         recipient         = getRecipient();
            RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);

            if (NotificationChannels.supported() && recipient.getNotificationChannel() != null) {
                recipientDatabase.setMessageRingtone(recipient.getId(), NotificationChannels.getMessageRingtone(context, recipient));
                recipientDatabase.setMessageVibrate(recipient.getId(), RecipientDatabase.VibrateState.fromBoolean(NotificationChannels.getMessageVibrate(context, recipient)));

                NotificationChannels.ensureCustomChannelConsistency(context);
            }

            onLoaded.run();
        });
    }

    void setHasCustomNotifications(final boolean hasCustomNotifications) {
        SignalExecutors.SERIAL.execute(() -> {
            if (hasCustomNotifications) {
                createCustomNotificationChannel();
            } else {
                deleteCustomNotificationChannel();
            }
        });
    }

    void setMessageVibrate(final RecipientDatabase.VibrateState vibrateState) {
        SignalExecutors.SERIAL.execute(() -> {
            Recipient recipient = getRecipient();

            DatabaseFactory.getRecipientDatabase(context).setMessageVibrate(recipient.getId(), vibrateState);
            NotificationChannels.updateMessageVibrate(context, recipient, vibrateState);
        });
    }

    void setCallingVibrate(final RecipientDatabase.VibrateState vibrateState) {
        SignalExecutors.SERIAL.execute(() -> DatabaseFactory.getRecipientDatabase(context).setCallVibrate(recipientId, vibrateState));
    }

    void setMessageSound(@Nullable Uri sound) {
        SignalExecutors.SERIAL.execute(() -> {
            Recipient recipient    = getRecipient();
            Uri       defaultValue = TextSecurePreferences.getNotificationRingtone(context);
            Uri       newValue;

            if (defaultValue.equals(sound)) newValue = null;
            else if (sound == null)         newValue = Uri.EMPTY;
            else                            newValue = sound;

            DatabaseFactory.getRecipientDatabase(context).setMessageRingtone(recipient.getId(), newValue);
            NotificationChannels.updateMessageRingtone(context, recipient, newValue);
        });
    }

    void setCallSound(@Nullable Uri sound) {
        SignalExecutors.SERIAL.execute(() -> {
            Uri defaultValue = TextSecurePreferences.getCallNotificationRingtone(context);
            Uri newValue;

            if (defaultValue.equals(sound)) newValue = null;
            else if (sound == null)         newValue = Uri.EMPTY;
            else                            newValue = sound;

            DatabaseFactory.getRecipientDatabase(context).setCallRingtone(recipientId, newValue);
        });
    }

    @WorkerThread
    private void createCustomNotificationChannel() {
        Recipient recipient = getRecipient();
        String    channelId = NotificationChannels.createChannelFor(context, recipient);

        DatabaseFactory.getRecipientDatabase(context).setNotificationChannel(recipient.getId(), channelId);
    }

    @WorkerThread
    private void deleteCustomNotificationChannel() {
        Recipient recipient = getRecipient();

        DatabaseFactory.getRecipientDatabase(context).setNotificationChannel(recipient.getId(), null);
        NotificationChannels.deleteChannelFor(context, recipient);
    }

    @WorkerThread
    private @NonNull Recipient getRecipient() {
        return Recipient.resolved(recipientId);
    }
}