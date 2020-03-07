package su.sres.securesms.profiles.edit;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobs.MultiDeviceProfileContentUpdateJob;
import su.sres.securesms.jobs.MultiDeviceProfileKeyUpdateJob;
import su.sres.securesms.jobs.ProfileUploadJob;
import su.sres.securesms.logging.Log;
import su.sres.securesms.profiles.AvatarHelper;
import su.sres.securesms.profiles.ProfileMediaConstraints;
import su.sres.securesms.profiles.ProfileName;
import su.sres.securesms.profiles.SystemProfileUtil;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.service.IncomingMessageObserver;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.Util;
import su.sres.securesms.util.concurrent.ListenableFuture;
import su.sres.securesms.util.concurrent.SignalExecutors;
import su.sres.securesms.util.concurrent.SimpleTask;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceMessagePipe;
import su.sres.signalservice.api.SignalServiceMessageReceiver;
import su.sres.signalservice.api.profiles.SignalServiceProfile;
import su.sres.signalservice.api.push.SignalServiceAddress;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

class EditProfileRepository {

    private static final String TAG = Log.tag(EditProfileRepository.class);

    private final Context context;
    private final boolean excludeSystem;

    EditProfileRepository(@NonNull Context context, boolean excludeSystem) {
        this.context        = context.getApplicationContext();
        this.excludeSystem  = excludeSystem;
    }

    void getCurrentProfileName(@NonNull Consumer<ProfileName> profileNameConsumer) {
        ProfileName storedProfileName = TextSecurePreferences.getProfileName(context);
        if (!storedProfileName.isEmpty()) {
            profileNameConsumer.accept(storedProfileName);
        } else if (!excludeSystem) {
            SystemProfileUtil.getSystemProfileName(context).addListener(new ListenableFuture.Listener<String>() {
                @Override
                public void onSuccess(String result) {
                    if (!TextUtils.isEmpty(result)) {
                        profileNameConsumer.accept(ProfileName.fromSerialized(result));
                    } else {
                        profileNameConsumer.accept(storedProfileName);
                    }
                }

                @Override
                public void onFailure(ExecutionException e) {
                    Log.w(TAG, e);
                    profileNameConsumer.accept(storedProfileName);
                }
            });
        } else {
            profileNameConsumer.accept(storedProfileName);
        }
    }

    void getCurrentAvatar(@NonNull Consumer<byte[]> avatarConsumer) {
        RecipientId selfId = Recipient.self().getId();

        if (AvatarHelper.getAvatarFile(context, selfId).exists() && AvatarHelper.getAvatarFile(context, selfId).length() > 0) {
            SimpleTask.run(() -> {
                try {
                    return Util.readFully(AvatarHelper.getInputStreamFor(context, selfId));
                } catch (IOException e) {
                    Log.w(TAG, e);
                    return null;
                }
            }, avatarConsumer::accept);
        } else if (!excludeSystem) {
            SystemProfileUtil.getSystemProfileAvatar(context, new ProfileMediaConstraints()).addListener(new ListenableFuture.Listener<byte[]>() {
                @Override
                public void onSuccess(byte[] result) {
                    avatarConsumer.accept(result);
                }

                @Override
                public void onFailure(ExecutionException e) {
                    Log.w(TAG, e);
                    avatarConsumer.accept(null);
                }
            });
        }
    }

    void uploadProfile(@NonNull ProfileName profileName, @Nullable byte[] avatar, @NonNull Consumer<UploadResult> uploadResultConsumer) {
        SimpleTask.run(() -> {
            TextSecurePreferences.setProfileName(context, profileName);
            DatabaseFactory.getRecipientDatabase(context).setProfileName(Recipient.self().getId(), profileName);

            try {
                AvatarHelper.setAvatar(context, Recipient.self().getId(), avatar);
                TextSecurePreferences.setProfileAvatarId(context, new SecureRandom().nextInt());
            } catch (IOException e) {
                return UploadResult.ERROR_FILE_IO;
            }

            ApplicationDependencies.getJobManager()
                    .startChain(new ProfileUploadJob())
                    .then(Arrays.asList(new MultiDeviceProfileKeyUpdateJob(), new MultiDeviceProfileContentUpdateJob()))
                    .enqueue();

            return UploadResult.SUCCESS;
        }, uploadResultConsumer::accept);
    }

    void getCurrentUsername(@NonNull Consumer<Optional<String>> callback) {
        callback.accept(Optional.fromNullable(TextSecurePreferences.getLocalUsername(context)));
        SignalExecutors.UNBOUNDED.execute(() -> callback.accept(getUsernameInternal()));
    }

    @WorkerThread
    private @NonNull Optional<String> getUsernameInternal() {
        try {
            SignalServiceProfile profile = retrieveOwnProfile();
            TextSecurePreferences.setLocalUsername(context, profile.getUsername());
            DatabaseFactory.getRecipientDatabase(context).setUsername(Recipient.self().getId(), profile.getUsername());
        } catch (IOException e) {
            Log.w(TAG, "Failed to retrieve username remotely! Using locally-cached version.");
        }
        return Optional.fromNullable(TextSecurePreferences.getLocalUsername(context));
    }

    private SignalServiceProfile retrieveOwnProfile() throws IOException {
        SignalServiceAddress         address  = new SignalServiceAddress(TextSecurePreferences.getLocalUuid(context), TextSecurePreferences.getLocalNumber(context));
        SignalServiceMessageReceiver receiver = ApplicationDependencies.getSignalServiceMessageReceiver();
        SignalServiceMessagePipe     pipe     = IncomingMessageObserver.getPipe();

        if (pipe != null) {
            try {
                return pipe.getProfile(address, Optional.absent());
            } catch (IOException e) {
                Log.w(TAG, e);
            }
        }

        return receiver.retrieveProfile(address, Optional.absent());
    }

    public enum UploadResult {
        SUCCESS,
        ERROR_FILE_IO
    }

}