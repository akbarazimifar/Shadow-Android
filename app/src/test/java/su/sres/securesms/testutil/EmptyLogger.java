package su.sres.securesms.testutil;

import su.sres.core.util.logging.Log;

public class EmptyLogger extends Log.Logger {
    @Override
    public void v(String tag, String message, Throwable t) { }

    @Override
    public void d(String tag, String message, Throwable t) { }

    @Override
    public void i(String tag, String message, Throwable t) { }

    @Override
    public void w(String tag, String message, Throwable t) { }

    @Override
    public void e(String tag, String message, Throwable t) { }

    @Override
    public void wtf(String tag, String message, Throwable t) { }

    @Override
    public void blockUntilAllWritesFinished() { }
}
