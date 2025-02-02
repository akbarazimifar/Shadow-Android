package su.sres.securesms.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import su.sres.core.util.StreamUtil;
import su.sres.securesms.R;
import su.sres.core.util.logging.Log;
import su.sres.securesms.mms.PartAuthority;
import su.sres.securesms.util.task.ProgressDialogAsyncTask;
import org.whispersystems.libsignal.util.Pair;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class SaveAttachmentTask extends ProgressDialogAsyncTask<SaveAttachmentTask.Attachment, Void, Pair<Integer, String>> {
  private static final String TAG = SaveAttachmentTask.class.getSimpleName();

          static final int SUCCESS              = 0;
  private static final int FAILURE              = 1;
  private static final int WRITE_ACCESS_FAILURE = 2;

  private final WeakReference<Context>      contextReference;

  private final int attachmentCount;

  public SaveAttachmentTask(Context context) {
    this(context, 1);
  }

  public SaveAttachmentTask(Context context, int count) {
    super(context,
          context.getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments, count, count),
          context.getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments_to_sd_card, count, count));
    this.contextReference      = new WeakReference<>(context);
    this.attachmentCount       = count;
  }

  @Override
  protected Pair<Integer, String> doInBackground(SaveAttachmentTask.Attachment... attachments) {
    if (attachments == null || attachments.length == 0) {
      throw new AssertionError("must pass in at least one attachment");
    }

    try {
      Context      context      = contextReference.get();
      String       directory    = null;

      if (!StorageUtil.canWriteToMediaStore()) {
        return new Pair<>(WRITE_ACCESS_FAILURE, null);
      }

      if (context == null) {
        return new Pair<>(FAILURE, null);
      }

      for (Attachment attachment : attachments) {
        if (attachment != null) {
          directory = saveAttachment(context, attachment);
          if (directory == null) return new Pair<>(FAILURE, null);
        }
      }

      if (attachments.length > 1) return new Pair<>(SUCCESS, null);
      else                        return new Pair<>(SUCCESS, directory);
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
      return new Pair<>(FAILURE, null);
    }
  }

  private @Nullable String saveAttachment(Context context, Attachment attachment) throws IOException
  {
    String      contentType = Objects.requireNonNull(MediaUtil.getCorrectedMimeType(attachment.contentType));
    String         fileName = attachment.fileName;

    if (fileName == null) fileName = generateOutputFileName(contentType, attachment.date);
    fileName = sanitizeOutputFileName(fileName);

    Uri           outputUri    = getMediaStoreContentUriForType(contentType);
    Uri           mediaUri     = createOutputUri(outputUri, contentType, fileName);
    ContentValues updateValues = new ContentValues();

    try (InputStream inputStream = PartAuthority.getAttachmentStream(context, attachment.uri)) {

      if (inputStream == null) {
        return null;
      }

      if (Objects.equals(outputUri.getScheme(), ContentResolver.SCHEME_FILE)) {
        try (OutputStream outputStream = new FileOutputStream(mediaUri.getPath())) {
          StreamUtil.copy(inputStream, outputStream);
          MediaScannerConnection.scanFile(context, new String[]{mediaUri.getPath()}, new String[]{contentType}, null);
        }
      } else {
        try (OutputStream outputStream = context.getContentResolver().openOutputStream(mediaUri, "w")) {
          long total = StreamUtil.copy(inputStream, outputStream);
          if (total > 0) {
            updateValues.put(MediaStore.MediaColumns.SIZE, total);
          }
        }
      }
    }

    if (Build.VERSION.SDK_INT > 28) {
      updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
    }

    if (updateValues.size() > 0) {
      getContext().getContentResolver().update(mediaUri, updateValues, null, null);
    }

    return outputUri.getLastPathSegment();
  }

  private @NonNull Uri getMediaStoreContentUriForType(@NonNull String contentType) {

    if (contentType.startsWith("video/")) {
      return StorageUtil.getVideoUri();
    } else if (contentType.startsWith("audio/")) {
      return StorageUtil.getAudioUri();
    } else if (contentType.startsWith("image/")) {
      return StorageUtil.getImageUri();
    } else {
      return StorageUtil.getDownloadUri();
    }
  }

  public String getExternalPathToFileForType(String contentType) {
    File storage;
    if (contentType.startsWith("video/")) {
      storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
    } else if (contentType.startsWith("audio/")) {
      storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
    } else if (contentType.startsWith("image/")) {
      storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
    } else {
      storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
    }
    return storage.getAbsolutePath();
  }

  private String generateOutputFileName(@NonNull String contentType, long timestamp) {
    MimeTypeMap      mimeTypeMap   = MimeTypeMap.getSingleton();
    String           extension     = mimeTypeMap.getExtensionFromMimeType(contentType);
    SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd-HHmmss");
    String           base          = "shadow-" + dateFormatter.format(timestamp);

    if (extension == null) extension = "attach";

    return base + "." + extension;
  }

  private String sanitizeOutputFileName(@NonNull String fileName) {
    return new File(fileName).getName();
  }

  private Uri createOutputUri(@NonNull Uri outputUri, @NonNull String contentType, @NonNull String fileName)
          throws IOException
  {
    String[] fileParts = getFileNameParts(fileName);
    String   base      = fileParts[0];
    String   extension = fileParts[1];
    String   mimeType  = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);

    ContentValues contentValues = new ContentValues();
    contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
    contentValues.put(MediaStore.MediaColumns.DATE_ADDED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
    contentValues.put(MediaStore.MediaColumns.DATE_MODIFIED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));

    if (Build.VERSION.SDK_INT > 28) {
      contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1);
    } else if (Objects.equals(outputUri.getScheme(), ContentResolver.SCHEME_FILE)) {
      File outputDirectory = new File(outputUri.getPath());
      File outputFile      = new File(outputDirectory, base + "." + extension);

      int i = 0;
      while (outputFile.exists()) {
        outputFile = new File(outputDirectory, base + "-" + (++i) + "." + extension);
      }

      if (outputFile.isHidden()) {
        throw new IOException("Specified name would not be visible");
      }

      return Uri.fromFile(outputFile);
    } else {
      String outputFileName = fileName;
      String dataPath       = String.format("%s/%s", getExternalPathToFileForType(contentType), outputFileName);
      int    i              = 0;
      while (pathTaken(outputUri, dataPath)) {
        Log.d(TAG, "The content exists. Rename and check again.");
        outputFileName = base + "-" + (++i) + "." + extension;
        dataPath       = String.format("%s/%s", getExternalPathToFileForType(contentType), outputFileName);
      }
      contentValues.put(MediaStore.MediaColumns.DATA, dataPath);
    }

    return getContext().getContentResolver().insert(outputUri, contentValues);
  }

  private boolean pathTaken(@NonNull Uri outputUri, @NonNull String dataPath) throws IOException {
    try (Cursor cursor = getContext().getContentResolver().query(outputUri,
            new String[] { MediaStore.MediaColumns.DATA },
            MediaStore.MediaColumns.DATA + " = ?",
            new String[] { dataPath },
            null))
    {
      if (cursor == null) {
        throw new IOException("Something is wrong with the filename to save");
      }
      return cursor.moveToFirst();
    }
  }

  private String[] getFileNameParts(String fileName) {
    String[] result = new String[2];
    String[] tokens = fileName.split("\\.(?=[^\\.]+$)");

    result[0] = tokens[0];

    if (tokens.length > 1) result[1] = tokens[1];
    else                   result[1] = "";

    return result;
  }

  @Override
  protected void onPostExecute(final Pair<Integer, String> result) {
    super.onPostExecute(result);
    final Context context = contextReference.get();
    if (context == null) return;

    switch (result.first()) {
      case FAILURE:
        Toast.makeText(context,
                       context.getResources().getQuantityText(R.plurals.ConversationFragment_error_while_saving_attachments_to_sd_card,
                                                              attachmentCount),
                       Toast.LENGTH_LONG).show();
        break;
      case SUCCESS:
        String message = !TextUtils.isEmpty(result.second())  ? context.getResources().getString(R.string.SaveAttachmentTask_saved_to, result.second())
                : context.getResources().getString(R.string.SaveAttachmentTask_saved);
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        break;
    }
  }

  public static class Attachment {
    public Uri    uri;
    public String fileName;
    public String contentType;
    public long   date;

    public Attachment(@NonNull Uri uri, @NonNull String contentType,
                      long date, @Nullable String fileName)
    {
      if (uri == null || contentType == null || date < 0) {
        throw new AssertionError("uri, content type, and date must all be specified");
      }
      this.uri         = uri;
      this.fileName    = fileName;
      this.contentType = contentType;
      this.date        = date;
    }
  }

  public static void showWarningDialog(Context context, OnClickListener onAcceptListener) {
    showWarningDialog(context, onAcceptListener, 1);
  }

  public static void showWarningDialog(Context context, OnClickListener onAcceptListener, int count) {
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(R.string.ConversationFragment_save_to_sd_card);
    builder.setIcon(R.drawable.ic_warning);
    builder.setCancelable(true);
    builder.setMessage(context.getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_media_to_storage_warning,
                                                                count, count));
    builder.setPositiveButton(R.string.yes, onAcceptListener);
    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }
}

