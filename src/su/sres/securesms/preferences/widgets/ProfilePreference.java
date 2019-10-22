package su.sres.securesms.preferences.widgets;

import android.content.Context;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import su.sres.securesms.R;
import su.sres.securesms.contacts.avatars.ProfileContactPhoto;
import su.sres.securesms.contacts.avatars.ResourceContactPhoto;
import su.sres.securesms.database.Address;
import su.sres.securesms.mms.GlideApp;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.TextSecurePreferences;

public class ProfilePreference extends Preference {

  private ImageView avatarView;
  private TextView  profileNameView;
  private TextView  profileNumberView;

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public ProfilePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize();
  }

  public ProfilePreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  public ProfilePreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public ProfilePreference(Context context) {
    super(context);
    initialize();
  }

  private void initialize() {
    setLayoutResource(R.layout.profile_preference_view);
  }

  @Override
  public void onBindViewHolder(PreferenceViewHolder viewHolder) {
    super.onBindViewHolder(viewHolder);
    avatarView        = (ImageView)viewHolder.findViewById(R.id.avatar);
    profileNameView   = (TextView)viewHolder.findViewById(R.id.profile_name);
    profileNumberView = (TextView)viewHolder.findViewById(R.id.number);

    refresh();
  }

  public void refresh() {
    if (profileNumberView == null) return;

    final Recipient self        = Recipient.self();
    final String    profileName = TextSecurePreferences.getProfileName(getContext());

    GlideApp.with(getContext().getApplicationContext())
            .load(new ProfileContactPhoto(self.getId(), String.valueOf(TextSecurePreferences.getProfileAvatarId(getContext()))))
            .error(new ResourceContactPhoto(R.drawable.ic_camera_solid_white_24).asDrawable(getContext(), getContext().getResources().getColor(R.color.grey_400)))
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(avatarView);

    if (!TextUtils.isEmpty(profileName)) {
      profileNameView.setText(profileName);
    }

    profileNumberView.setText(self.requireAddress().toPhoneString());
  }
}
