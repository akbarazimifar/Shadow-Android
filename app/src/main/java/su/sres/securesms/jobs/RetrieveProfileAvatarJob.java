package su.sres.securesms.jobs;

import androidx.annotation.NonNull;
import android.text.TextUtils;

import org.signal.zkgroup.profiles.ProfileKey;
import su.sres.securesms.crypto.ProfileKeyUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;

import su.sres.securesms.profiles.AvatarHelper;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.Util;
import su.sres.signalservice.api.SignalServiceMessageReceiver;
import su.sres.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class RetrieveProfileAvatarJob extends BaseJob  {

  public static final String KEY = "RetrieveProfileAvatarJob";

  private static final String TAG = RetrieveProfileAvatarJob.class.getSimpleName();

  private static final int MAX_PROFILE_SIZE_BYTES = 20 * 1024 * 1024;

  private static final String KEY_PROFILE_AVATAR = "profile_avatar";
  private static final String KEY_RECIPIENT      = "recipient";

  private final String    profileAvatar;
  private final Recipient recipient;

  public RetrieveProfileAvatarJob(Recipient recipient, String profileAvatar) {
    this(new Job.Parameters.Builder()
                    .setQueue("RetrieveProfileAvatarJob::" + recipient.getId().toQueueKey())
                    .addConstraint(NetworkConstraint.KEY)
                    .setLifespan(TimeUnit.HOURS.toMillis(1))
                    .build(),
            recipient,
            profileAvatar);
  }

  private RetrieveProfileAvatarJob(@NonNull Job.Parameters parameters, @NonNull Recipient recipient, String profileAvatar) {
    super(parameters);

    this.recipient     = recipient;
    this.profileAvatar = profileAvatar;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_PROFILE_AVATAR, profileAvatar)
            .putString(KEY_RECIPIENT, recipient.getId().serialize())
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    RecipientDatabase database   = DatabaseFactory.getRecipientDatabase(context);
    ProfileKey        profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.resolve().getProfileKey());

    if (profileKey == null) {
      Log.w(TAG, "Recipient profile key is gone!");
      return;
    }

    if (Util.equals(profileAvatar, recipient.resolve().getProfileAvatar())) {
      Log.w(TAG, "Already retrieved profile avatar: " + profileAvatar);
      return;
    }

    if (TextUtils.isEmpty(profileAvatar)) {
      Log.w(TAG, "Removing profile avatar (no url) for: " + recipient.getId().serialize());
      AvatarHelper.delete(context, recipient.getId());
      database.setProfileAvatar(recipient.getId(), profileAvatar);
      return;
    }

    File downloadDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

    try {
      SignalServiceMessageReceiver receiver     = ApplicationDependencies.getSignalServiceMessageReceiver();
      InputStream                  avatarStream = receiver.retrieveProfileAvatar(profileAvatar, downloadDestination, profileKey, AvatarHelper.AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE);

      try {
        AvatarHelper.setAvatar(context, recipient.getId(), avatarStream);
      } catch (AssertionError e) {
        throw new IOException("Failed to copy stream. Likely a Conscrypt issue.", e);
      }
    } catch (PushNetworkException e) {
      if (e.getCause() instanceof NonSuccessfulResponseCodeException) {
        Log.w(TAG, "Removing profile avatar (no image available) for: " + recipient.getId().serialize());
        AvatarHelper.delete(context, recipient.getId());
      } else {
        throw e;
      }
    } finally {
      if (downloadDestination != null) downloadDestination.delete();
    }

    database.setProfileAvatar(recipient.getId(), profileAvatar);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<RetrieveProfileAvatarJob> {

    @Override
    public @NonNull RetrieveProfileAvatarJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new RetrieveProfileAvatarJob(parameters,
              Recipient.resolved(RecipientId.from(data.getString(KEY_RECIPIENT))),
              data.getString(KEY_PROFILE_AVATAR));
    }
  }
}
