package su.sres.securesms.components;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import androidx.appcompat.app.AlertDialog;
import su.sres.core.util.logging.Log;
import android.widget.Toast;

import su.sres.securesms.BuildConfig;
import su.sres.securesms.R;
import su.sres.securesms.util.PlayStoreUtil;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.VersionTracker;

import java.util.concurrent.TimeUnit;

public class RatingManager {

  private static final int DAYS_SINCE_INSTALL_THRESHOLD  = 7;
  private static final int DAYS_UNTIL_REPROMPT_THRESHOLD = 4;

  private static final String TAG = RatingManager.class.getSimpleName();

  public static void showRatingDialogIfNecessary(Context context) {
    if (!TextSecurePreferences.isRatingEnabled(context) || BuildConfig.PLAY_STORE_DISABLED) return;

    long daysSinceInstall = VersionTracker.getDaysSinceFirstInstalled(context);
    long laterTimestamp   = TextSecurePreferences.getRatingLaterTimestamp(context);

    if (daysSinceInstall >= DAYS_SINCE_INSTALL_THRESHOLD &&
        System.currentTimeMillis() >= laterTimestamp)
    {
      showRatingDialog(context);
    }
  }

  private static void showRatingDialog(final Context context) {
    new AlertDialog.Builder(context)
        .setTitle(R.string.RatingManager_rate_this_app)
        .setMessage(R.string.RatingManager_if_you_enjoy_using_this_app_please_take_a_moment)
        .setPositiveButton(R.string.RatingManager_rate_now, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            TextSecurePreferences.setRatingEnabled(context, false);
            PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(context);
         }
       })
       .setNegativeButton(R.string.RatingManager_no_thanks, new DialogInterface.OnClickListener() {
         @Override
         public void onClick(DialogInterface dialog, int which) {
           TextSecurePreferences.setRatingEnabled(context, false);
         }
       })
       .setNeutralButton(R.string.RatingManager_later, new DialogInterface.OnClickListener() {
         @Override
         public void onClick(DialogInterface dialog, int which) {
           long waitUntil = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(DAYS_UNTIL_REPROMPT_THRESHOLD);
           TextSecurePreferences.setRatingLaterTimestamp(context, waitUntil);
         }
       })
       .show();
  }
}
