package su.sres.securesms.jobs;


import android.app.Application;
import androidx.annotation.NonNull;
import android.text.TextUtils;

import su.sres.securesms.database.Address;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.dependencies.InjectableType;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.logging.Log;

import su.sres.securesms.profiles.AvatarHelper;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.Util;
import su.sres.signalservice.api.SignalServiceMessageReceiver;
import su.sres.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

public class RetrieveProfileAvatarJob extends BaseJob implements InjectableType {

  public static final String KEY = "RetrieveProfileAvatarJob";

  private static final String TAG = RetrieveProfileAvatarJob.class.getSimpleName();

  private static final int MAX_PROFILE_SIZE_BYTES = 20 * 1024 * 1024;

  private static final String KEY_PROFILE_AVATAR = "profile_avatar";
  private static final String KEY_ADDRESS        = "address";


  @Inject SignalServiceMessageReceiver receiver;

  private String    profileAvatar;
  private Recipient recipient;

  public RetrieveProfileAvatarJob(Recipient recipient, String profileAvatar) {
    this(new Job.Parameters.Builder()
                    .setQueue("RetrieveProfileAvatarJob" + recipient.getAddress().serialize())
                    .addConstraint(NetworkConstraint.KEY)
                    .setLifespan(TimeUnit.HOURS.toMillis(1))
                    .setMaxInstances(1)
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
            .putString(KEY_ADDRESS, recipient.getAddress().serialize())
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    RecipientDatabase database   = DatabaseFactory.getRecipientDatabase(context);
    byte[]            profileKey = recipient.resolve().getProfileKey();

    if (profileKey == null) {
      Log.w(TAG, "Recipient profile key is gone!");
      return;
    }

    if (Util.equals(profileAvatar, recipient.resolve().getProfileAvatar())) {
      Log.w(TAG, "Already retrieved profile avatar: " + profileAvatar);
      return;
    }

    if (TextUtils.isEmpty(profileAvatar)) {
      Log.w(TAG, "Removing profile avatar (no url) for: " + recipient.getAddress().serialize());
      AvatarHelper.delete(context, recipient.getAddress());
      database.setProfileAvatar(recipient, profileAvatar);
      return;
    }

    File downloadDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

    try {
      InputStream avatarStream       = receiver.retrieveProfileAvatar(profileAvatar, downloadDestination, profileKey, MAX_PROFILE_SIZE_BYTES);
      File        decryptDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

      try {
        Util.copy(avatarStream, new FileOutputStream(decryptDestination));
      } catch (AssertionError e) {
        throw new IOException("Failed to copy stream. Likely a Conscrypt issue.", e);
      }

      decryptDestination.renameTo(AvatarHelper.getAvatarFile(context, recipient.getAddress()));
    } catch (PushNetworkException e) {
      if (e.getCause() instanceof NonSuccessfulResponseCodeException) {
        Log.w(TAG, "Removing profile avatar (no image available) for: " + recipient.getAddress().serialize());
        AvatarHelper.delete(context, recipient.getAddress());
      } else {
        throw e;
      }
    } finally {
      if (downloadDestination != null) downloadDestination.delete();
    }

    database.setProfileAvatar(recipient, profileAvatar);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
  }

  public static final class Factory implements Job.Factory<RetrieveProfileAvatarJob> {

    private final Application application;

    public Factory(Application application) {
      this.application = application;
    }

    @Override
    public @NonNull RetrieveProfileAvatarJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new RetrieveProfileAvatarJob(parameters,
              Recipient.from(application, Address.fromSerialized(data.getString(KEY_ADDRESS)), true),
              data.getString(KEY_PROFILE_AVATAR));
    }
  }
}
