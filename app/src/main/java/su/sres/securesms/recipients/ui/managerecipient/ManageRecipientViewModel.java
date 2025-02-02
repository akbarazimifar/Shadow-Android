package su.sres.securesms.recipients.ui.managerecipient;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.annimon.stream.Stream;

import su.sres.core.util.ThreadUtil;
import su.sres.securesms.BlockUnblockDialog;
import su.sres.securesms.ExpirationDialog;
import su.sres.securesms.R;
import su.sres.securesms.VerifyIdentityActivity;
import su.sres.securesms.database.IdentityDatabase;
import su.sres.securesms.database.MediaDatabase;
import su.sres.securesms.database.loaders.MediaLoader;
import su.sres.securesms.database.loaders.ThreadMediaLoader;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.ui.GroupMemberEntry;
import su.sres.securesms.groups.ui.addtogroup.AddToGroupsActivity;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.phonenumbers.PhoneNumberFormatter;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.util.Base64;
import su.sres.securesms.util.CommunicationActions;
import su.sres.securesms.util.DefaultValueLiveData;
import su.sres.securesms.util.ExpirationUtil;
import su.sres.securesms.util.Hex;
import su.sres.securesms.util.Util;
import su.sres.securesms.util.livedata.LiveDataUtil;

import java.util.List;
import java.util.UUID;

public final class ManageRecipientViewModel extends ViewModel {

    private static final int MAX_UNCOLLAPSED_GROUPS = 6;
    private static final int SHOW_COLLAPSED_GROUPS  = 5;

    private final Context                                          context;
    private final ManageRecipientRepository                        manageRecipientRepository;
    private final LiveData<String>                                 title;
    private final LiveData<String>                                 subtitle;
    private final LiveData<String>                                 internalDetails;
    private final LiveData<String>                                 disappearingMessageTimer;
    private final MutableLiveData<IdentityDatabase.IdentityRecord> identity;
    private final LiveData<Recipient>                              recipient;
    private final MutableLiveData<MediaCursor>                     mediaCursor;
    private final LiveData<MuteState>                              muteState;
    private final LiveData<Boolean>                                hasCustomNotifications;
    private final LiveData<Boolean>                                canCollapseMemberList;
    private final DefaultValueLiveData<CollapseState>              groupListCollapseState;
    private final LiveData<Boolean>                                canBlock;
    private final LiveData<Boolean>                                canUnblock;
    private final LiveData<List<GroupMemberEntry.FullMember>>      visibleSharedGroups;
    private final LiveData<String>                                 sharedGroupsCountSummary;
    private final LiveData<Boolean>                                canAddToAGroup;

    private ManageRecipientViewModel(@NonNull Context context, @NonNull ManageRecipientRepository manageRecipientRepository) {
        this.context                   = context;
        this.manageRecipientRepository = manageRecipientRepository;
        this.recipient                 = Recipient.live(manageRecipientRepository.getRecipientId()).getLiveData();
        this.title                     = Transformations.map(recipient, r -> getDisplayTitle(r, context)   );
        this.subtitle                  = Transformations.map(recipient, r -> getDisplaySubtitle(r, context));
        this.identity                  = new MutableLiveData<>();
        this.mediaCursor               = new MutableLiveData<>(null);
        this.groupListCollapseState    = new DefaultValueLiveData<>(CollapseState.COLLAPSED);
        this.disappearingMessageTimer  = Transformations.map(this.recipient, r -> ExpirationUtil.getExpirationDisplayValue(context, r.getExpireMessages()));
        this.muteState                 = Transformations.map(this.recipient, r -> new MuteState(r.getMuteUntil(), r.isMuted()));
        this.hasCustomNotifications    = Transformations.map(this.recipient, r -> r.getNotificationChannel() != null || !NotificationChannels.supported());
        this.canBlock                  = Transformations.map(this.recipient, r -> RecipientUtil.isBlockable(r) && !r.isBlocked());
        this.canUnblock                = Transformations.map(this.recipient, Recipient::isBlocked);
        this.internalDetails           = Transformations.map(this.recipient, this::populateInternalDetails);

        manageRecipientRepository.getThreadId(this::onThreadIdLoaded);

        LiveData<List<Recipient>> allSharedGroups = LiveDataUtil.mapAsync(this.recipient, r -> manageRecipientRepository.getSharedGroups(r.getId()));

        this.sharedGroupsCountSummary = Transformations.map(allSharedGroups, list -> {
            int size = list.size();
            return size == 0 ? context.getString(R.string.ManageRecipientActivity_no_groups_in_common)
                    : context.getResources().getQuantityString(R.plurals.ManageRecipientActivity_d_groups_in_common, size, size);
        });

        this.canCollapseMemberList = LiveDataUtil.combineLatest(this.groupListCollapseState,
                Transformations.map(allSharedGroups, m -> m.size() > MAX_UNCOLLAPSED_GROUPS),
                (state, hasEnoughMembers) -> state != CollapseState.OPEN && hasEnoughMembers);
        this.visibleSharedGroups   = Transformations.map(LiveDataUtil.combineLatest(allSharedGroups,
                this.groupListCollapseState,
                ManageRecipientViewModel::filterSharedGroupList),
                recipients -> Stream.of(recipients).map(r -> new GroupMemberEntry.FullMember(r, false)).toList());

        boolean isSelf = manageRecipientRepository.getRecipientId().equals(Recipient.self().getId());
        if (!isSelf) {
            manageRecipientRepository.getIdentity(identity::postValue);
        }

        MutableLiveData<Integer> localGroupCount = new MutableLiveData<>(0);

        this.canAddToAGroup = LiveDataUtil.combineLatest(recipient,
                localGroupCount,
                (r, count) -> count > 0 && r.isRegistered() && !r.isGroup() && !r.isSelf());

        manageRecipientRepository.getActiveGroupCount(localGroupCount::postValue);
    }

    private static @NonNull String getDisplayTitle(@NonNull Recipient recipient, @NonNull Context context) {
        if (recipient.isSelf()) {
            return context.getString(R.string.note_to_self);
        } else {
            return recipient.getDisplayName(context);
        }
    }

    private static @NonNull String getDisplaySubtitle(@NonNull Recipient recipient, @NonNull Context context) {
        if (!recipient.isSelf() && recipient.hasAUserSetDisplayName(context)) {
            return recipient.getSmsAddress().or("").trim();
        } else {
            return "";
        }
    }

    @WorkerThread
    private void onThreadIdLoaded(long threadId) {
        mediaCursor.postValue(new MediaCursor(threadId,
                () -> new ThreadMediaLoader(context, threadId, MediaLoader.MediaType.GALLERY, MediaDatabase.Sorting.Newest).getCursor()));
    }

    LiveData<String> getTitle() {
        return title;
    }

    LiveData<String> getSubtitle() {
        return subtitle;
    }

    LiveData<String> getInternalDetails() {
        return internalDetails;
    }

    LiveData<Recipient> getRecipient() {
        return recipient;
    }

    LiveData<Boolean> getCanAddToAGroup() {
        return canAddToAGroup;
    }

    LiveData<MediaCursor> getMediaCursor() {
        return mediaCursor;
    }

    LiveData<MuteState> getMuteState() {
        return muteState;
    }

    LiveData<String> getDisappearingMessageTimer() {
        return disappearingMessageTimer;
    }

    LiveData<Boolean> hasCustomNotifications() {
        return hasCustomNotifications;
    }

    LiveData<Boolean> getCanCollapseMemberList() {
        return canCollapseMemberList;
    }

    LiveData<Boolean> getCanBlock() {
        return canBlock;
    }

    LiveData<Boolean> getCanUnblock() {
        return canUnblock;
    }

    void handleExpirationSelection(@NonNull Context context) {
        withRecipient(recipient ->
                ExpirationDialog.show(context,
                        recipient.getExpireMessages(),
                        manageRecipientRepository::setExpiration));
    }

    void setMuteUntil(long muteUntil) {
        manageRecipientRepository.setMuteUntil(muteUntil);
    }

    void clearMuteUntil() {
        manageRecipientRepository.setMuteUntil(0);
    }

    void revealCollapsedMembers() {
        groupListCollapseState.setValue(CollapseState.OPEN);
    }

    void onAddToGroupButton(@NonNull Activity activity) {
        manageRecipientRepository.getGroupMembership(existingGroups -> ThreadUtil.runOnMain(() -> activity.startActivity(AddToGroupsActivity.newIntent(activity, manageRecipientRepository.getRecipientId(), existingGroups))));
    }

    private void withRecipient(@NonNull Consumer<Recipient> mainThreadRecipientCallback) {
        manageRecipientRepository.getRecipient(recipient -> ThreadUtil.runOnMain(() -> mainThreadRecipientCallback.accept(recipient)));
    }

    private static @NonNull List<Recipient> filterSharedGroupList(@NonNull List<Recipient> groups,
                                                                  @NonNull CollapseState collapseState)
    {
        if (collapseState == CollapseState.COLLAPSED && groups.size() > MAX_UNCOLLAPSED_GROUPS) {
            return groups.subList(0, SHOW_COLLAPSED_GROUPS);
        } else {
            return groups;
        }
    }

    LiveData<IdentityDatabase.IdentityRecord> getIdentity() {
        return identity;
    }

    void onBlockClicked(@NonNull FragmentActivity activity) {
        withRecipient(recipient -> BlockUnblockDialog.showBlockFor(activity, activity.getLifecycle(), recipient, () -> RecipientUtil.blockNonGroup(context, recipient)));
    }

    void onUnblockClicked(@NonNull FragmentActivity activity) {
        withRecipient(recipient -> BlockUnblockDialog.showUnblockFor(activity, activity.getLifecycle(), recipient, () -> RecipientUtil.unblock(context, recipient)));
    }

    void onViewSafetyNumberClicked(@NonNull Activity activity, @NonNull IdentityDatabase.IdentityRecord identityRecord) {
        activity.startActivity(VerifyIdentityActivity.newIntent(activity, identityRecord));
    }

    LiveData<List<GroupMemberEntry.FullMember>> getVisibleSharedGroups() {
        return visibleSharedGroups;
    }

    LiveData<String> getSharedGroupsCountSummary() {
        return sharedGroupsCountSummary;
    }

    void onSelectColor(int color) {
        manageRecipientRepository.setColor(color);
    }

    void onGroupClicked(@NonNull Activity activity, @NonNull Recipient recipient) {
        CommunicationActions.startConversation(activity, recipient, null);
        activity.finish();
    }

    void onMessage(@NonNull FragmentActivity activity) {
        withRecipient(r -> {
            CommunicationActions.startConversation(activity, r, null);
            activity.finish();
        });
    }

    void onSecureCall(@NonNull FragmentActivity activity) {
        withRecipient(r -> CommunicationActions.startVoiceCall(activity, r));
    }

    void onSecureVideoCall(@NonNull FragmentActivity activity) {
        withRecipient(r -> CommunicationActions.startVideoCall(activity, r));
    }

    private @NonNull String populateInternalDetails(@NonNull Recipient recipient) {
        if (!SignalStore.internalValues().recipientDetails()) {
            return "";
        }

        String profileKeyBase64 = recipient.getProfileKey() != null ? Base64.encodeBytes(recipient.getProfileKey()) : "None";
        String profileKeyHex    = recipient.getProfileKey() != null ? Hex.toStringCondensed(recipient.getProfileKey()) : "None";
        return String.format("-- Profile Name --\n[%s] [%s]\n\n" +
                        "-- Profile Sharing --\n%s\n\n" +
                        "-- Profile Key (Base64) --\n%s\n\n" +
                        "-- Profile Key (Hex) --\n%s\n\n" +
                        "-- Sealed Sender Mode --\n%s\n\n" +
                        "-- UUID --\n%s\n\n" +
                        "-- RecipientId --\n%s",
                recipient.getProfileName().getGivenName(), recipient.getProfileName().getFamilyName(),
                recipient.isProfileSharing(),
                profileKeyBase64,
                profileKeyHex,
                recipient.getUnidentifiedAccessMode(),
                recipient.getUuid().transform(UUID::toString).or("None"),
                recipient.getId().serialize());
    }

    static final class MediaCursor {
        private final long          threadId;
        @NonNull private final CursorFactory mediaCursorFactory;

        private MediaCursor(long threadId,
                            @NonNull CursorFactory mediaCursorFactory)
        {
            this.threadId           = threadId;
            this.mediaCursorFactory = mediaCursorFactory;
        }

        long getThreadId() {
            return threadId;
        }

        @NonNull CursorFactory getMediaCursorFactory() {
            return mediaCursorFactory;
        }
    }

    static final class MuteState {
        private final long    mutedUntil;
        private final boolean isMuted;

        MuteState(long mutedUntil, boolean isMuted) {
            this.mutedUntil = mutedUntil;
            this.isMuted    = isMuted;
        }

        long getMutedUntil() {
            return mutedUntil;
        }

        public boolean isMuted() {
            return isMuted;
        }
    }

    private enum CollapseState {
        OPEN,
        COLLAPSED
    }

    interface CursorFactory {
        Cursor create();
    }

    public static class Factory implements ViewModelProvider.Factory {
        private final Context     context;
        private final RecipientId recipientId;

        public Factory(@NonNull RecipientId recipientId) {
            this.context     = ApplicationDependencies.getApplication();
            this.recipientId = recipientId;
        }

        @Override
        public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            //noinspection unchecked
            return (T) new ManageRecipientViewModel(context, new ManageRecipientRepository(context, recipientId));
        }
    }
}