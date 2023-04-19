package su.sres.securesms.messages;

import android.content.Context;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import su.sres.securesms.ApplicationContext;
import su.sres.securesms.R;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;
import su.sres.securesms.service.DelayedNotificationController;
import su.sres.securesms.service.GenericForegroundService;
import su.sres.securesms.util.PowerManagerCompat;
import su.sres.securesms.util.ServiceUtil;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.WakeLockUtil;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Retrieves messages while the app is in the background via provided {@link MessageRetrievalStrategy}'s.
 */
public class BackgroundMessageRetriever {

    private static final String TAG = Log.tag(BackgroundMessageRetriever.class);

    private static final String WAKE_LOCK_TAG = "MessageRetriever";

    private static final Semaphore ACTIVE_LOCK = new Semaphore(2);

    private static final long NORMAL_TIMEOUT = TimeUnit.SECONDS.toMillis(10);

    public static final long DO_NOT_SHOW_IN_FOREGROUND = DelayedNotificationController.DO_NOT_SHOW;

    /**
     * @return False if the retrieval failed and should be rescheduled, otherwise true.
     */
    @WorkerThread
    public boolean retrieveMessages(@NonNull Context context, MessageRetrievalStrategy... strategies) {
        return retrieveMessages(context, DO_NOT_SHOW_IN_FOREGROUND, strategies);
    }

    /**
     * @return False if the retrieval failed and should be rescheduled, otherwise true.
     */
    @WorkerThread
    public boolean retrieveMessages(@NonNull Context context, long showNotificationAfterMs, MessageRetrievalStrategy... strategies) {
        if (shouldIgnoreFetch(context)) {
            Log.i(TAG, "Skipping retrieval -- app is in the foreground.");
            return true;
        }

        if (!ACTIVE_LOCK.tryAcquire()) {
            Log.i(TAG, "Skipping retrieval -- there's already one enqueued.");
            return true;
        }

        synchronized (this) {
            try (DelayedNotificationController controller = GenericForegroundService.startForegroundTaskDelayed(context, context.getString(R.string.BackgroundMessageRetriever_checking_for_messages), showNotificationAfterMs, R.drawable.ic_signal_refresh)) {
                PowerManager.WakeLock wakeLock = null;

                try {
                    wakeLock = WakeLockUtil.acquire(context, PowerManager.PARTIAL_WAKE_LOCK, TimeUnit.SECONDS.toMillis(60), WAKE_LOCK_TAG);

                    TextSecurePreferences.setNeedsMessagePull(context, true);

                    long startTime = System.currentTimeMillis();
                    PowerManager powerManager = ServiceUtil.getPowerManager(context);
                    boolean doze = PowerManagerCompat.isDeviceIdleMode(powerManager);
                    boolean network = new NetworkConstraint.Factory(ApplicationContext.getInstance(context)).create().isMet();

                    if (doze || !network) {
                        Log.w(TAG, "We may be operating in a constrained environment. Doze: " + doze + " Network: " + network);
                    }

                    Log.i(TAG, "Performing normal message fetch.");
                    return executeBackgroundRetrieval(context, startTime, strategies);
                } finally {
                    WakeLockUtil.release(wakeLock, WAKE_LOCK_TAG);
                    ACTIVE_LOCK.release();
                }
            }
        }
    }

    private boolean executeBackgroundRetrieval(@NonNull Context context, long startTime, @NonNull MessageRetrievalStrategy[] strategies) {
        boolean success = false;

        for (MessageRetrievalStrategy strategy : strategies) {
            if (shouldIgnoreFetch(context)) {
                Log.i(TAG, "Stopping further strategy attempts -- app is in the foreground." + logSuffix(startTime));
                success = true;
                break;
            }

            Log.i(TAG, "Attempting strategy: " + strategy.toString() + logSuffix(startTime));

            if (strategy.execute(NORMAL_TIMEOUT)) {
                Log.i(TAG, "Strategy succeeded: " + strategy.toString() + logSuffix(startTime));
                success = true;
                break;
            } else {
                Log.w(TAG, "Strategy failed: " + strategy.toString() + logSuffix(startTime));
            }
        }

        if (success) {
            TextSecurePreferences.setNeedsMessagePull(context, false);
        } else {
            Log.w(TAG, "All strategies failed!" + logSuffix(startTime));
        }

        return success;
    }

    /**
     * @return True if there is no need to execute a message fetch, because the websocket will take
     * care of it.
     */
    public static boolean shouldIgnoreFetch(@NonNull Context context) {
        return ApplicationDependencies.getAppForegroundObserver().isForegrounded();
    }

    private static String logSuffix(long startTime) {
        return " (" + (System.currentTimeMillis() - startTime) + " ms elapsed)";
    }
}