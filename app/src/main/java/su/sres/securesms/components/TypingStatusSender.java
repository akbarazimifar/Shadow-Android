package su.sres.securesms.components;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.annotation.NonNull;

import su.sres.core.util.ThreadUtil;
import su.sres.securesms.ApplicationContext;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobs.TypingSendJob;
import su.sres.securesms.util.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SuppressLint("UseSparseArrays")
public class TypingStatusSender {

    private static final String TAG = TypingStatusSender.class.getSimpleName();

    private static final long REFRESH_TYPING_TIMEOUT = TimeUnit.SECONDS.toMillis(10);
    private static final long PAUSE_TYPING_TIMEOUT   = TimeUnit.SECONDS.toMillis(3);

    private final Map<Long, TimerPair> selfTypingTimers;

    public TypingStatusSender() {
        this.selfTypingTimers = new HashMap<>();
    }

    public synchronized void onTypingStarted(long threadId) {
        TimerPair pair = Util.getOrDefault(selfTypingTimers, threadId, new TimerPair());
        selfTypingTimers.put(threadId, pair);

        if (pair.getStart() == null) {
            sendTyping(threadId, true);

            Runnable start = new StartRunnable(threadId);
            ThreadUtil.runOnMainDelayed(start, REFRESH_TYPING_TIMEOUT);
            pair.setStart(start);
        }

        if (pair.getStop() != null) {
            ThreadUtil.cancelRunnableOnMain(pair.getStop());
        }

        Runnable stop = () -> onTypingStopped(threadId, true);
        ThreadUtil.runOnMainDelayed(stop, PAUSE_TYPING_TIMEOUT);
        pair.setStop(stop);
    }

    public synchronized void onTypingStoppedWithNotify(long threadId) {
        onTypingStopped(threadId, true);
    }

    public synchronized void onTypingStopped(long threadId) {
        onTypingStopped(threadId, false);
    }

    private synchronized void onTypingStopped(long threadId, boolean notify) {
        TimerPair pair = Util.getOrDefault(selfTypingTimers, threadId, new TimerPair());
        selfTypingTimers.put(threadId, pair);

        if (pair.getStart() != null) {
            ThreadUtil.cancelRunnableOnMain(pair.getStart());

            if (notify) {
                sendTyping(threadId, false);
            }
        }

        if (pair.getStop() != null) {
            ThreadUtil.cancelRunnableOnMain(pair.getStop());
        }

        pair.setStart(null);
        pair.setStop(null);
    }

    private void sendTyping(long threadId, boolean typingStarted) {
        ApplicationDependencies.getJobManager().add(new TypingSendJob(threadId, typingStarted));
    }

    private class StartRunnable implements Runnable {

        private final long threadId;

        private StartRunnable(long threadId) {
            this.threadId = threadId;
        }

        @Override
        public void run() {
            sendTyping(threadId, true);
            ThreadUtil.runOnMainDelayed(this, REFRESH_TYPING_TIMEOUT);
        }
    }

    private static class TimerPair {
        private Runnable start;
        private Runnable stop;

        public Runnable getStart() {
            return start;
        }

        public void setStart(Runnable start) {
            this.start = start;
        }

        public Runnable getStop() {
            return stop;
        }

        public void setStop(Runnable stop) {
            this.stop = stop;
        }
    }
}