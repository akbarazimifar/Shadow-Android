package su.sres.securesms.mms;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import su.sres.securesms.database.model.MessageRecord;
import su.sres.core.util.logging.Log;

import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;

/**
 * Represents the information required to find the {@link MessageRecord} pointed to by a quote.
 */
public class QuoteId {

  private static final String TAG = QuoteId.class.getSimpleName();

  private static final String ID                 = "id";
  private static final String AUTHOR_DEPRECATED  = "author";
  private static final String AUTHOR             = "author_id";

  private final long        id;
  private final RecipientId author;

  public QuoteId(long id, @NonNull RecipientId author) {
    this.id     = id;
    this.author = author;
  }

  public long getId() {
    return id;
  }

  public @NonNull RecipientId getAuthor() {
    return author;
  }

  public @NonNull String serialize() {
    try {
      JSONObject object = new JSONObject();
      object.put(ID, id);
      object.put(AUTHOR, author.serialize());
      return object.toString();
    } catch (JSONException e) {
      Log.e(TAG, "Failed to serialize to json", e);
      return "";
    }
  }

  public static @Nullable QuoteId deserialize(@NonNull Context context, @NonNull String serialized) {
    try {
      JSONObject  json = new JSONObject(serialized);
      RecipientId id   = json.has(AUTHOR) ? RecipientId.from(json.getString(AUTHOR))
              : Recipient.external(context, json.getString(AUTHOR_DEPRECATED)).getId();

      return new QuoteId(json.getLong(ID), id);
    } catch (JSONException e) {
      Log.e(TAG, "Failed to deserialize from json", e);
      return null;
    }
  }
}
