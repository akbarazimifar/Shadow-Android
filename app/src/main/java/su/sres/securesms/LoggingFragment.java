package su.sres.securesms;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import su.sres.core.util.logging.Log;

/**
 * Simply logs out lifecycle events.
 */
public abstract class LoggingFragment extends Fragment {

    private static final String TAG = Log.tag(LoggingFragment.class);

    public LoggingFragment() { }

    public LoggingFragment(int contentLayoutId) {
        super(contentLayoutId);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        logEvent("onCreate()");
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStart() {
        logEvent("onStart()");
        super.onStart();
    }

    @Override
    public void onStop() {
        logEvent("onStop()");
        super.onStop();
    }

    @Override
    public void onDestroy() {
        logEvent("onDestroy()");
        super.onDestroy();
    }

    private void logEvent(@NonNull String event) {
        Log.d(TAG, "[" + Log.tag(getClass()) + "] " + event);
    }
}