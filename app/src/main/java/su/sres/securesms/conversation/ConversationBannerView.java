package su.sres.securesms.conversation;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import su.sres.securesms.R;
import su.sres.securesms.components.AvatarImageView;
import su.sres.securesms.contacts.avatars.FallbackContactPhoto;
import su.sres.securesms.contacts.avatars.ResourceContactPhoto;
import su.sres.securesms.mms.GlideRequests;
import su.sres.securesms.recipients.Recipient;

public class ConversationBannerView extends ConstraintLayout {

    private AvatarImageView contactAvatar;
    private TextView        contactTitle;
    private TextView        contactAbout;
    private TextView        contactSubtitle;
    private TextView        contactDescription;

    public ConversationBannerView(Context context) {
        this(context, null);
    }

    public ConversationBannerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ConversationBannerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        inflate(getContext(), R.layout.conversation_banner_view, this);

        contactAvatar      = findViewById(R.id.message_request_avatar);
        contactTitle       = findViewById(R.id.message_request_title);
        contactAbout       = findViewById(R.id.message_request_about);
        contactSubtitle    = findViewById(R.id.message_request_subtitle);
        contactDescription = findViewById(R.id.message_request_description);

        contactAvatar.setFallbackPhotoProvider(new FallbackPhotoProvider());
    }

    public void setAvatar(@NonNull GlideRequests requests, @Nullable Recipient recipient) {
        contactAvatar.setAvatar(requests, recipient, false);
    }

    public void setTitle(@Nullable CharSequence title) {
        contactTitle.setText(title);
    }

    public void setAbout(@Nullable String about) {
        contactAbout.setText(about);
        contactAbout.setVisibility(TextUtils.isEmpty(about) ? GONE : VISIBLE);
    }

    public void setSubtitle(@Nullable CharSequence subtitle) {
        contactSubtitle.setText(subtitle);
        contactSubtitle.setVisibility(TextUtils.isEmpty(subtitle) ? GONE : VISIBLE);
    }

    public void setDescription(@Nullable CharSequence description) {
        contactDescription.setText(description);
    }

    public void showBackgroundBubble(boolean enabled) {
        if (enabled) {
            setBackgroundResource(R.drawable.wallpaper_bubble_background_12);
        } else {
            setBackground(null);
        }
    }

    public void hideSubtitle() {
        contactSubtitle.setVisibility(View.GONE);
    }

    public void showDescription() {
        contactDescription.setVisibility(View.VISIBLE);
    }

    public void hideDescription() {
        contactDescription.setVisibility(View.GONE);
    }

    private static final class FallbackPhotoProvider extends Recipient.FallbackPhotoProvider {
        @Override
        public @NonNull FallbackContactPhoto getPhotoForRecipientWithoutName() {
            return new ResourceContactPhoto(R.drawable.ic_profile_80);
        }

        @Override
        public @NonNull FallbackContactPhoto getPhotoForGroup() {
            return new ResourceContactPhoto(R.drawable.ic_group_80);
        }

        @Override
        public @NonNull FallbackContactPhoto getPhotoForLocalNumber() {
            return new ResourceContactPhoto(R.drawable.ic_note_80);
        }
    }
}