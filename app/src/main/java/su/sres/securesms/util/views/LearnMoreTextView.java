package su.sres.securesms.util.views;

import android.content.Context;
import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatTextView;

import su.sres.securesms.R;
import su.sres.securesms.util.ThemeUtil;

public class LearnMoreTextView extends AppCompatTextView {

    private OnClickListener linkListener;
    private Spannable       link;
    private boolean         visible;
    private CharSequence    baseText;

    public LearnMoreTextView(Context context) {
        super(context);
        init();
    }

    public LearnMoreTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setMovementMethod(LinkMovementMethod.getInstance());

        setLinkTextInternal(R.string.LearnMoreTextView_learn_more);

        visible = true;
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        baseText = text;
        setTextInternal(baseText, type);
    }

    @Override
    public void setTextColor(int color) {
        super.setTextColor(color);
    }

    public void setOnLinkClickListener(@Nullable OnClickListener listener) {
        this.linkListener = listener;
    }

    public void setLearnMoreVisible(boolean visible) {
        this.visible = visible;
        setTextInternal(baseText, visible ? BufferType.SPANNABLE : BufferType.NORMAL);
    }

    public void setLearnMoreVisible(boolean visible, @StringRes int linkText) {
        setLinkTextInternal(linkText);
        this.visible = visible;
        setTextInternal(baseText, visible ? BufferType.SPANNABLE : BufferType.NORMAL);
    }

    private void setLinkTextInternal(@StringRes int linkText) {
        ClickableSpan clickable = new ClickableSpan() {
            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                ds.setUnderlineText(false);
                ds.setColor(ThemeUtil.getThemedColor(getContext(), R.attr.colorAccent));
            }

            @Override
            public void onClick(@NonNull View widget) {
                if (linkListener != null) {
                    linkListener.onClick(widget);
                }
            }
        };

        link = new SpannableString(getContext().getString(linkText));
        link.setSpan(clickable, 0, link.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    private void setTextInternal(CharSequence text, BufferType type) {
        if (visible) {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            builder.append(text).append(' ').append(link);

            super.setText(builder, BufferType.SPANNABLE);
        } else {
            super.setText(text, type);
        }
    }
}