package su.sres.securesms.database.model;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;

import androidx.annotation.AnyThread;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;

import com.annimon.stream.Stream;

import su.sres.securesms.R;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.ContextUtil;
import su.sres.securesms.util.SpanUtil;
import su.sres.securesms.util.ThemeUtil;
import su.sres.securesms.util.livedata.LiveDataUtil;
import org.whispersystems.libsignal.util.guava.Function;

import java.util.List;

public final class LiveUpdateMessage {

    /**
     * Creates a live data that observes the recipients mentioned in the {@link UpdateDescription} and
     * recreates the string asynchronously when they change.
     */
    @AnyThread
    public static LiveData<SpannableString> fromMessageDescription(@NonNull Context context, @NonNull UpdateDescription updateDescription, @ColorInt int defaultTint) {
        if (updateDescription.isStringStatic()) {
            return LiveDataUtil.just(toSpannable(context, updateDescription, updateDescription.getStaticString(), defaultTint));
        }

        List<LiveData<Recipient>> allMentionedRecipients = Stream.of(updateDescription.getMentioned())
                .map(uuid -> Recipient.resolved(RecipientId.from(uuid, null)).live().getLiveData())
                .toList();

        LiveData<?> mentionedRecipientChangeStream = allMentionedRecipients.isEmpty() ? LiveDataUtil.just(new Object())
                : LiveDataUtil.merge(allMentionedRecipients);

        return LiveDataUtil.mapAsync(mentionedRecipientChangeStream, event -> toSpannable(context, updateDescription, updateDescription.getString(), defaultTint));
    }

    /**
     * Observes a single recipient and recreates the string asynchronously when they change.
     */
    public static LiveData<SpannableString> recipientToStringAsync(@NonNull RecipientId recipientId,
                                                                   @NonNull Function<Recipient, SpannableString> createStringInBackground)
    {
        return LiveDataUtil.mapAsync(Recipient.live(recipientId).getLiveDataResolved(), createStringInBackground);
    }

    private static @NonNull SpannableString toSpannable(@NonNull Context context, @NonNull UpdateDescription updateDescription, @NonNull String string, @ColorInt int defaultTint) {
        boolean  isDarkTheme      = ThemeUtil.isDarkTheme(context);
        int      drawableResource = updateDescription.getIconResource();
        int      tint             = isDarkTheme ? updateDescription.getDarkTint() : updateDescription.getLightTint();

        if (tint == 0) {
            tint = defaultTint;
        }

        if (drawableResource == 0) {
            return new SpannableString(string);
        } else {
            Drawable drawable = ContextUtil.requireDrawable(context, drawableResource);
            drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            drawable.setColorFilter(tint, PorterDuff.Mode.SRC_ATOP);

            Spannable stringWithImage = new SpannableStringBuilder().append(SpanUtil.buildImageSpan(drawable)).append("  ").append(string);

            return new SpannableString(SpanUtil.color(tint, stringWithImage));
        }
    }

}