package su.sres.securesms.logsubmit;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationManagerCompat;

import su.sres.securesms.util.ServiceUtil;
import su.sres.securesms.util.TextSecurePreferences;

final class LogSectionNotifications implements LogSection {

    @Override
    public @NonNull String getTitle() {
        return "NOTIFICATIONS";
    }

    @Override
    public @NonNull CharSequence getContent(@NonNull Context context) {
        StringBuilder output = new StringBuilder();

        output.append("Message notifications: ").append(TextSecurePreferences.isNotificationsEnabled(context)).append("\n")
                .append("Call notifications   : ").append(TextSecurePreferences.isCallNotificationsEnabled(context)).append("\n")
                .append("In-chat sounds       : ").append(TextSecurePreferences.isInThreadNotifications(context)).append("\n")
                .append("Repeat alerts        : ").append(TextSecurePreferences.getRepeatAlertsCount(context)).append("\n")
                .append("Notification display : ").append(TextSecurePreferences.getNotificationPrivacy(context)).append("\n\n");

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = ServiceUtil.getNotificationManager(context);
            for (NotificationChannel channel : manager.getNotificationChannels()) {
                output.append(buildChannelString(channel));
            }
        }


        return output;
    }

    @RequiresApi(26)
    private static @NonNull String buildChannelString(@NonNull NotificationChannel channel) {
        return  "-- " + channel.getId() + "\n" +
                "importance          : " + importanceString(channel.getImportance()) + "\n" +
                "hasUserSetImportance: " + (Build.VERSION.SDK_INT >= 29 ? channel.hasUserSetImportance() : "N/A (Requires API 29)") + "\n" +
                "hasUserSetSound     : " + (Build.VERSION.SDK_INT >= 30 ? channel.hasUserSetSound() : "N/A (Requires API 30)") + "\n" +
                "shouldVibrate       : " + channel.shouldVibrate() + "\n" +
                "shouldShowLights    : " + channel.shouldShowLights() + "\n" +
                "canBypassDnd        : " + channel.canBypassDnd() + "\n" +
                "canShowBadge        : " + channel.canShowBadge() + "\n" +
                "canBubble           : " + (Build.VERSION.SDK_INT >= 29 ? channel.canBubble() : "N/A (Requires API 29)") + "\n\n";
    }

    private static @NonNull String importanceString(int value) {
        switch (value) {
            case NotificationManager.IMPORTANCE_NONE:    return "NONE (0)";
            case NotificationManager.IMPORTANCE_MIN:     return "MIN (1)";
            case NotificationManager.IMPORTANCE_LOW:     return "LOW (2)";
            case NotificationManager.IMPORTANCE_DEFAULT: return "DEFAULT (3)";
            case NotificationManager.IMPORTANCE_HIGH:    return "HIGH (4)";
            case NotificationManager.IMPORTANCE_MAX:     return "MAX (5)";
            default:                                     return "UNSPECIFIED (" + value + ")";
        }
    }
}
