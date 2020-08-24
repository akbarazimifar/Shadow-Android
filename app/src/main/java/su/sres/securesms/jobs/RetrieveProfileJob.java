package su.sres.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import android.content.Context;
import android.text.TextUtils;

import com.annimon.stream.Stream;

import org.signal.zkgroup.profiles.ProfileKey;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import su.sres.securesms.crypto.ProfileKeyUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.logging.Log;
import su.sres.securesms.profiles.ProfileName;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.transport.RetryLaterException;
import su.sres.securesms.util.Base64;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.IdentityUtil;
import su.sres.securesms.util.ProfileUtil;
import su.sres.securesms.util.Stopwatch;
import su.sres.securesms.util.Util;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.securesms.util.concurrent.SignalExecutors;
import su.sres.signalservice.api.crypto.InvalidCiphertextException;
import su.sres.signalservice.api.crypto.ProfileCipher;
import su.sres.signalservice.api.profiles.ProfileAndCredential;
import su.sres.signalservice.api.profiles.SignalServiceProfile;
import su.sres.signalservice.api.push.exceptions.NotFoundException;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;
import su.sres.signalservice.internal.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Retrieves a users profile and sets the appropriate local fields.
 */
public class RetrieveProfileJob extends BaseJob  {

  public static final String KEY = "RetrieveProfileJob";

  private static final String TAG = RetrieveProfileJob.class.getSimpleName();

  private static final String KEY_RECIPIENTS = "recipients";

  private final List<RecipientId> recipientIds;

  /**
   * Identical to {@link #enqueue(Collection)})}, but run on a background thread for convenience.
   */
  public static void enqueueAsync(@NonNull RecipientId recipientId) {
    SignalExecutors.BOUNDED.execute(() -> {
      ApplicationDependencies.getJobManager().add(forRecipient(recipientId));
    });
  }

  /**
   * Submits the necessary jobs to refresh the profiles of the requested recipients. Works for any
   * RecipientIds, including individuals, groups, or yourself.
   */
  @WorkerThread
  public static void enqueue(@NonNull Collection<RecipientId> recipientIds) {
    Context context    = ApplicationDependencies.getApplication();
    JobManager jobManager = ApplicationDependencies.getJobManager();
    List<RecipientId> combined   = new LinkedList<>();

    for (RecipientId recipientId : recipientIds) {
      Recipient recipient = Recipient.resolved(recipientId);

      if (recipient.isLocalNumber()) {
        jobManager.add(new RefreshOwnProfileJob());
      } else if (recipient.isGroup()) {
        List<Recipient> recipients = DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipient.requireGroupId(), GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);
        combined.addAll(Stream.of(recipients).map(Recipient::getId).toList());
      } else {
        combined.add(recipientId);
      }
    }

    jobManager.add(new RetrieveProfileJob(combined));
  }

  /**
   * Works for any RecipientId, whether it's an individual, group, or yourself.
   */
  @WorkerThread
  public static @NonNull Job forRecipient(@NonNull RecipientId recipientId) {
    Recipient recipient = Recipient.resolved(recipientId);

    if (recipient.isLocalNumber()) {
      return new RefreshOwnProfileJob();
    } else if (recipient.isGroup()) {
      Context         context    = ApplicationDependencies.getApplication();
      List<Recipient> recipients = DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipient.requireGroupId(), GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);

      return new RetrieveProfileJob(Stream.of(recipients).map(Recipient::getId).toList());
    } else {
      return new RetrieveProfileJob(Collections.singletonList(recipientId));
    }
  }

  private RetrieveProfileJob(@NonNull List<RecipientId> recipientIds) {
    this(new Job.Parameters.Builder()
                    .addConstraint(NetworkConstraint.KEY)
                    .setMaxAttempts(3)
                    .build(),
         recipientIds);
  }

  private RetrieveProfileJob(@NonNull Job.Parameters parameters, @NonNull List<RecipientId> recipientIds) {
    super(parameters);

    this.recipientIds = recipientIds;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder()
            .putStringListAsArray(KEY_RECIPIENTS, Stream.of(recipientIds)
                    .map(RecipientId::serialize)
                    .toList())
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, RetryLaterException {
    Stopwatch stopwatch = new Stopwatch("RetrieveProfile");
    Set<RecipientId> retries   = new HashSet<>();

    List<Recipient> recipients = Stream.of(recipientIds).map(Recipient::resolved).toList();
    stopwatch.split("resolve");

    List<Pair<Recipient, ListenableFuture<ProfileAndCredential>>> futures = Stream.of(recipients)
            .filter(Recipient::hasServiceIdentifier)
            .map(r -> new Pair<>(r, ProfileUtil.retrieveProfile(context, r, getRequestType(r))))
            .toList();
    stopwatch.split("futures");

    List<Pair<Recipient, ProfileAndCredential>> profiles = Stream.of(futures)
            .map(pair -> {
              Recipient recipient = pair.first();

              try {
                ProfileAndCredential profile = pair.second().get(5, TimeUnit.SECONDS);
                return new Pair<>(recipient, profile);
              } catch (InterruptedException | TimeoutException e) {
                retries.add(recipient.getId());
              } catch (ExecutionException e) {
                if (e.getCause() instanceof PushNetworkException) {
                  retries.add(recipient.getId());
                } else if (e.getCause() instanceof NotFoundException) {
                  Log.w(TAG, "Failed to find a profile for " + recipient.getId());
                } else {
                  Log.w(TAG, "Failed to retrieve profile for " + recipient.getId());
                }
              }
              return null;
            })
            .withoutNulls()
            .toList();
    stopwatch.split("network");

    for (Pair<Recipient, ProfileAndCredential> profile : profiles) {
      process(profile.first(), profile.second());
    }

    stopwatch.split("process");

    long keyCount = Stream.of(profiles).map(Pair::first).map(Recipient::getProfileKey).withoutNulls().count();
    Log.d(TAG, String.format(Locale.US, "Started with %d recipient(s). Found %d profile(s), and had keys for %d of them. Will retry %d.", recipients.size(), profiles.size(), keyCount, retries.size()));

    stopwatch.stop(TAG);
    recipientIds.clear();
    recipientIds.addAll(retries);

    if (recipientIds.size() > 0) {
      throw new RetryLaterException();
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof RetryLaterException;
  }

  @Override
  public void onFailure() {}

  private void process(Recipient recipient, ProfileAndCredential profileAndCredential) throws IOException {
    SignalServiceProfile profile              = profileAndCredential.getProfile();
    ProfileKey           recipientProfileKey  = ProfileKeyUtil.profileKeyOrNull(recipient.getProfileKey());

    setProfileName(recipient, profile.getName());
    setProfileAvatar(recipient, profile.getAvatar());
    if (FeatureFlags.usernames()) setUsername(recipient, profile.getUsername());
    setProfileCapabilities(recipient, profile.getCapabilities());
    setIdentityKey(recipient, profile.getIdentityKey());
    setUnidentifiedAccessMode(recipient, profile.getUnidentifiedAccess(), profile.isUnrestrictedUnidentifiedAccess());

    if (recipientProfileKey != null) {
      Optional<ProfileKeyCredential> profileKeyCredential = profileAndCredential.getProfileKeyCredential();
      if (profileKeyCredential.isPresent()) {
        setProfileKeyCredential(recipient, recipientProfileKey, profileKeyCredential.get());
      }
    }
  }

  private void setProfileKeyCredential(@NonNull Recipient recipient,
                                       @NonNull ProfileKey recipientProfileKey,
                                       @NonNull ProfileKeyCredential credential)
  {
    RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    recipientDatabase.setProfileKeyCredential(recipient.getId(), recipientProfileKey, credential);
  }

  private static SignalServiceProfile.RequestType getRequestType(@NonNull Recipient recipient) {
    return FeatureFlags.versionedProfiles() && !recipient.hasProfileKeyCredential()
            ? SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL
            : SignalServiceProfile.RequestType.PROFILE;
  }

  private void setIdentityKey(Recipient recipient, String identityKeyValue) {
    try {
      if (TextUtils.isEmpty(identityKeyValue)) {
        Log.w(TAG, "Identity key is missing on profile!");
        return;
      }

      IdentityKey identityKey = new IdentityKey(Base64.decode(identityKeyValue), 0);

      if (!DatabaseFactory.getIdentityDatabase(context)
              .getIdentity(recipient.getId())
                          .isPresent())
      {
        Log.w(TAG, "Still first use...");
        return;
      }

      IdentityUtil.saveIdentity(context, recipient.requireServiceId(), identityKey);
    } catch (InvalidKeyException | IOException e) {
      Log.w(TAG, e);
    }
  }

  private void setUnidentifiedAccessMode(Recipient recipient, String unidentifiedAccessVerifier, boolean unrestrictedUnidentifiedAccess) {
    RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    ProfileKey        profileKey        = ProfileKeyUtil.profileKeyOrNull(recipient.getProfileKey());

    if (unrestrictedUnidentifiedAccess && unidentifiedAccessVerifier != null) {
      if (recipient.getUnidentifiedAccessMode() != UnidentifiedAccessMode.UNRESTRICTED) {
        Log.i(TAG, "Marking recipient UD status as unrestricted.");
        recipientDatabase.setUnidentifiedAccessMode(recipient.getId(), UnidentifiedAccessMode.UNRESTRICTED);
      }
    } else if (profileKey == null || unidentifiedAccessVerifier == null) {
      if (recipient.getUnidentifiedAccessMode() != UnidentifiedAccessMode.DISABLED) {
        Log.i(TAG, "Marking recipient UD status as disabled.");
        recipientDatabase.setUnidentifiedAccessMode(recipient.getId(), UnidentifiedAccessMode.DISABLED);
      }
    } else {
      ProfileCipher profileCipher = new ProfileCipher(profileKey);
      boolean verifiedUnidentifiedAccess;

      try {
        verifiedUnidentifiedAccess = profileCipher.verifyUnidentifiedAccess(Base64.decode(unidentifiedAccessVerifier));
      } catch (IOException e) {
        Log.w(TAG, e);
        verifiedUnidentifiedAccess = false;
      }

      UnidentifiedAccessMode mode = verifiedUnidentifiedAccess ? UnidentifiedAccessMode.ENABLED : UnidentifiedAccessMode.DISABLED;

      if (recipient.getUnidentifiedAccessMode() != mode) {
        Log.i(TAG, "Marking recipient UD status as " + mode.name() + " after verification.");
        recipientDatabase.setUnidentifiedAccessMode(recipient.getId(), mode);
      }
    }
  }

  private void setProfileName(Recipient recipient, String profileName) {
    try {
      ProfileKey profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.getProfileKey());
      if (profileKey == null) return;

      String plaintextProfileName = ProfileUtil.decryptName(profileKey, profileName);

      if (!Objects.equals(plaintextProfileName, recipient.getProfileName().serialize())) {
        Log.i(TAG, "Profile name updated. Writing new value.");
        DatabaseFactory.getRecipientDatabase(context).setProfileName(recipient.getId(), ProfileName.fromSerialized(plaintextProfileName));
      }
      if (TextUtils.isEmpty(plaintextProfileName)) {
        Log.i
                (TAG, "No profile name set.");
      }

    } catch (InvalidCiphertextException e) {
      Log.w(TAG, "Bad profile key for " + recipient.getId());
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  private void setProfileAvatar(Recipient recipient, String profileAvatar) {
    if (recipient.getProfileKey() == null) return;

    if (!Util.equals(profileAvatar, recipient.getProfileAvatar())) {
      ApplicationDependencies.getJobManager().add(new RetrieveProfileAvatarJob(recipient, profileAvatar));
    }
  }

  private void setUsername(Recipient recipient, @Nullable String username) {
    DatabaseFactory.getRecipientDatabase(context).setUsername(recipient.getId(), username);
  }

  // maybe later...
/*  private void setUuid(Recipient recipient, UUID uuid) {
    if (uuid !=null && !recipient.getUuid().isPresent()) {
      DatabaseFactory.getRecipientDatabase(context).setUuid(recipient.getId(), uuid);
    } else {

      if(uuid == null) {
        Log.i(TAG, "UUID is null");
      }

      if (recipient.getUuid().isPresent()) {
        Log.i(TAG, "Recipient UUID is present");
      }

    }
  } */

  private void setProfileCapabilities(@NonNull Recipient recipient, @Nullable SignalServiceProfile.Capabilities capabilities) {
    if (capabilities == null) {
      return;
    }

    DatabaseFactory.getRecipientDatabase(context).setCapabilities(recipient.getId(), capabilities);
  }

  public static final class Factory implements Job.Factory<RetrieveProfileJob> {

    @Override
    public @NonNull RetrieveProfileJob create(@NonNull Parameters parameters, @NonNull Data data) {
      String[]          ids          = data.getStringArray(KEY_RECIPIENTS);
      List<RecipientId> recipientIds = Stream.of(ids).map(RecipientId::from).toList();

      return new RetrieveProfileJob(parameters, recipientIds);
    }
  }
}
