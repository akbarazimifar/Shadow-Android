package su.sres.core.util.logging;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Looper;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@SuppressLint("LogNotShadow")
public final class PersistentLogger extends Log.Logger {

  private static final String TAG     = PersistentLogger.class.getSimpleName();

  private static final String LOG_V   = "V";
  private static final String LOG_D   = "D";
  private static final String LOG_I   = "I";
  private static final String LOG_W   = "W";
  private static final String LOG_E   = "E";
  private static final String LOG_WTF = "A";

  private static final String           LOG_DIRECTORY   = "log";
  private static final String           FILENAME_PREFIX = "log-";
  private static final int              MAX_LOG_FILES   = 7;
  private static final int              MAX_LOG_SIZE    = 300 * 1024;
  private static final SimpleDateFormat DATE_FORMAT     = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS zzz", Locale.US);

  private final Context  context;
  private final Executor executor;
  private final byte[]   secret;
  private final String   logTag;

  private LogFile.Writer writer;

  private ThreadLocal<String> cachedThreadString;

  public PersistentLogger(@NonNull Context context, @NonNull byte[] secret, @NonNull String logTag) {
    this.context            = context.getApplicationContext();
    this.secret             = secret;
    this.logTag             = logTag;
    this.cachedThreadString = new ThreadLocal<>();
    this.executor           = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "shadow-PersistentLogger");
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    });

    executor.execute(this::initializeWriter);
  }

  @Override
  public void v(String tag, String message, Throwable t) {
    write(LOG_V, tag, message, t);
  }

  @Override
  public void d(String tag, String message, Throwable t) {
    write(LOG_D, tag, message, t);
  }

  @Override
  public void i(String tag, String message, Throwable t) {
    write(LOG_I, tag, message, t);
  }

  @Override
  public void w(String tag, String message, Throwable t) {
    write(LOG_W, tag, message, t);
  }

  @Override
  public void e(String tag, String message, Throwable t) {
    write(LOG_E, tag, message, t);
  }

  @Override
  public void wtf(String tag, String message, Throwable t) {
    write(LOG_WTF, tag, message, t);
  }

  @Override
  public void blockUntilAllWritesFinished() {
    CountDownLatch latch = new CountDownLatch(1);

    executor.execute(latch::countDown);

    try {
      latch.await();
    } catch (InterruptedException e) {
      android.util.Log.w(TAG, "Failed to wait for all writes.");
    }
  }

  @WorkerThread
  public @Nullable CharSequence getLogs() {
    CountDownLatch                latch = new CountDownLatch(1);
    AtomicReference<CharSequence> logs  = new AtomicReference<>();

    executor.execute(() -> {
      StringBuilder builder = new StringBuilder();

      try {
        File[] logFiles = getSortedLogFiles();
        for (int i = logFiles.length - 1; i >= 0; i--) {
          try {
            LogFile.Reader reader = new LogFile.Reader(secret, logFiles[i]);
            builder.append(reader.readAll());
          } catch (IOException e) {
            android.util.Log.w(TAG, "Failed to read log at index " + i + ". Removing reference.");
            logFiles[i].delete();
          }
        }

        logs.set(builder);
      } catch (IOException e) {
        logs.set(null);
      }

      latch.countDown();
    });

    try {
      latch.await();
      return logs.get();
    } catch (InterruptedException e) {
      android.util.Log.w(TAG, "Failed to wait for logs to be retrieved.");
      return null;
    }
  }

  @WorkerThread
  private void initializeWriter() {
    try {
      writer = new LogFile.Writer(secret, getOrCreateActiveLogFile());
    } catch (IOException e) {
      android.util.Log.e(TAG, "Failed to initialize writer.", e);
    }
  }

  @AnyThread
  private void write(String level, String tag, String message, Throwable t) {
    String threadString = cachedThreadString.get();

    if (cachedThreadString.get() == null) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        threadString = "main ";
      } else {
        threadString = String.format("%-5s", Thread.currentThread().getId());
      }

      cachedThreadString.set(threadString);
    }

    final String finalThreadString = threadString;
    executor.execute(() -> {
      try {
        if (writer == null) {
          return;
        }

        if (writer.getLogSize() >= MAX_LOG_SIZE) {
          writer.close();
          writer = new LogFile.Writer(secret, createNewLogFile());
          trimLogFilesOverMax();
        }

        for (String entry : buildLogEntries(level, tag, message, t, finalThreadString)) {
          writer.writeEntry(entry);
        }
      } catch (IOException e) {
        android.util.Log.w(TAG, "Failed to write line. Deleting all logs and starting over.");
        deleteAllLogs();
        initializeWriter();
      }
    });
  }

  private void trimLogFilesOverMax() throws IOException {
    File[] logs = getSortedLogFiles();
    if (logs.length > MAX_LOG_FILES) {
      for (int i = MAX_LOG_FILES; i < logs.length; i++) {
        logs[i].delete();
      }
    }
  }

  private void deleteAllLogs() {
    try {
      File[] logs = getSortedLogFiles();
      for (File log : logs) {
        log.delete();
      }
    } catch (IOException e) {
      android.util.Log.w(TAG, "Was unable to delete logs.", e);
    }
  }

  private File getOrCreateActiveLogFile() throws IOException {
    File[] logs = getSortedLogFiles();
    if (logs.length > 0) {
      return logs[0];
    }

    return createNewLogFile();
  }

  private File createNewLogFile() throws IOException {
    return new File(getOrCreateLogDirectory(), FILENAME_PREFIX + System.currentTimeMillis());
  }

  private File[] getSortedLogFiles() throws IOException {
    File[] logs = getOrCreateLogDirectory().listFiles();
    if (logs != null) {
      Arrays.sort(logs, (o1, o2) -> o2.getName().compareTo(o1.getName()));
      return logs;
    }
    return new File[0];
  }

  private File getOrCreateLogDirectory() throws IOException {
    File logDir = new File(context.getCacheDir(), LOG_DIRECTORY);
    if (!logDir.exists() && !logDir.mkdir()) {
      throw new IOException("Unable to create log directory.");
    }

    return logDir;
  }

  private List<String> buildLogEntries(String level, String tag, String message, Throwable t, String threadString) {
    List<String> entries = new LinkedList<>();
    Date         date    = new Date();

    entries.add(buildEntry(level, tag, message, date, threadString));

    if (t != null) {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      t.printStackTrace(new PrintStream(outputStream));

      String   trace = new String(outputStream.toByteArray());
      String[] lines = trace.split("\\n");

      for (String line : lines) {
        entries.add(buildEntry(level, tag, line, date, threadString));
      }
    }

    return entries;
  }

  private String buildEntry(String level, String tag, String message, Date date, String threadString) {
    return '[' + logTag + "] [" + threadString + "] " + DATE_FORMAT.format(date) + ' ' + level + ' ' + tag + ": " + message;
  }
}
