package su.sres.securesms.components;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.AttributeSet;

import su.sres.securesms.R;
import su.sres.securesms.util.Util;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

public class ExpirationTimerView extends androidx.appcompat.widget.AppCompatImageView {

  private long startedAt;
  private long expiresIn;

  private boolean visible = false;
  private boolean stopped = true;

  private final int[] frames = new int[]{ R.drawable.ic_timer_00_16,
          R.drawable.ic_timer_05_16,
          R.drawable.ic_timer_10_16,
          R.drawable.ic_timer_15_16,
          R.drawable.ic_timer_20_16,
          R.drawable.ic_timer_25_16,
          R.drawable.ic_timer_30_16,
          R.drawable.ic_timer_35_16,
          R.drawable.ic_timer_40_16,
          R.drawable.ic_timer_45_16,
          R.drawable.ic_timer_50_16,
          R.drawable.ic_timer_55_16,
          R.drawable.ic_timer_60_16 };

  public ExpirationTimerView(Context context) {
    super(context);
  }

  public ExpirationTimerView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  public ExpirationTimerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  public void setExpirationTime(long startedAt, long expiresIn) {
    this.startedAt = startedAt;
    this.expiresIn = expiresIn;
    setPercentComplete(calculateProgress(this.startedAt, this.expiresIn));
  }

  public void setPercentComplete(float percentage) {
    float percentFull = 1 - percentage;
    int frame = (int) Math.ceil(percentFull * (frames.length - 1));

    frame = Math.max(0, Math.min(frame, frames.length - 1));
    setImageResource(frames[frame]);
  }

  public void startAnimation() {
    synchronized (this) {
      visible = true;
      if (!stopped) return;
      else          stopped = false;
    }

    Util.runOnMainDelayed(new AnimationUpdateRunnable(this), calculateAnimationDelay(this.startedAt, this.expiresIn));
  }

  public void stopAnimation() {
    synchronized (this) {
      visible = false;
    }
  }

  private float calculateProgress(long startedAt, long expiresIn) {
    long  progressed      = System.currentTimeMillis() - startedAt;
    float percentComplete = (float)progressed / (float)expiresIn;

    return Math.max(0, Math.min(percentComplete, 1));
  }

  private long calculateAnimationDelay(long startedAt, long expiresIn) {
    long progressed = System.currentTimeMillis() - startedAt;
    long remaining  = expiresIn - progressed;

    if (remaining < TimeUnit.SECONDS.toMillis(30)) {
      return 50;
    } else {
      return 1000;
    }
  }

  private static class AnimationUpdateRunnable implements Runnable {

    private final WeakReference<ExpirationTimerView> expirationTimerViewReference;

    private AnimationUpdateRunnable(@NonNull ExpirationTimerView expirationTimerView) {
      this.expirationTimerViewReference = new WeakReference<>(expirationTimerView);
    }

    @Override
    public void run() {
      ExpirationTimerView timerView = expirationTimerViewReference.get();
      if (timerView == null) return;

      timerView.setExpirationTime(timerView.startedAt, timerView.expiresIn);

      synchronized (timerView) {
        if (!timerView.visible) {
          timerView.stopped = true;
          return;
        }
      }

      Util.runOnMainDelayed(this, timerView.calculateAnimationDelay(timerView.startedAt, timerView.expiresIn));
    }
  }

}
