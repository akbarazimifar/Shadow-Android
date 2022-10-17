package su.sres.securesms.groups;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.annimon.stream.ComparatorCompat;
import com.annimon.stream.Stream;

import su.sres.securesms.R;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.ui.GroupMemberEntry;
import su.sres.securesms.groups.v2.GroupInviteLinkUrl;
import su.sres.securesms.groups.v2.GroupLinkUrlAndStatus;
import su.sres.securesms.recipients.LiveRecipient;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.concurrent.SignalExecutors;
import su.sres.securesms.util.livedata.LiveDataUtil;
import su.sres.signalservice.api.util.UuidUtil;
import su.sres.storageservice.protos.groups.AccessControl;
import su.sres.storageservice.protos.groups.local.DecryptedGroup;
import su.sres.storageservice.protos.groups.local.DecryptedRequestingMember;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class LiveGroup {

    private static final Comparator<GroupMemberEntry.FullMember>         LOCAL_FIRST       = (m1, m2) -> Boolean.compare(m2.getMember().isLocalNumber(), m1.getMember().isLocalNumber());
    private static final Comparator<GroupMemberEntry.FullMember>         ADMIN_FIRST       = (m1, m2) -> Boolean.compare(m2.isAdmin(), m1.isAdmin());
    private static final Comparator<GroupMemberEntry.FullMember>         HAS_DISPLAY_NAME  = (m1, m2) -> Boolean.compare(m2.getMember().hasAUserSetDisplayName(ApplicationDependencies.getApplication()), m1.getMember().hasAUserSetDisplayName(ApplicationDependencies.getApplication()));
    private static final Comparator<GroupMemberEntry.FullMember>         ALPHABETICAL      = (m1, m2) -> m1.getMember().getDisplayName(ApplicationDependencies.getApplication()).compareToIgnoreCase(m2.getMember().getDisplayName(ApplicationDependencies.getApplication()));
    private static final Comparator<? super GroupMemberEntry.FullMember> MEMBER_ORDER      = ComparatorCompat.chain(LOCAL_FIRST)
            .thenComparing(ADMIN_FIRST)
            .thenComparing(HAS_DISPLAY_NAME)
            .thenComparing(ALPHABETICAL);

    private final GroupDatabase                                     groupDatabase;
    private final LiveData<Recipient>                               recipient;
    private final LiveData<GroupDatabase.GroupRecord>               groupRecord;
    private final LiveData<List<GroupMemberEntry.FullMember>>       fullMembers;
    private final LiveData<List<GroupMemberEntry.RequestingMember>> requestingMembers;
    private final LiveData<GroupLinkUrlAndStatus>                   groupLink;

    public LiveGroup(@NonNull GroupId groupId) {
        Context                        context       = ApplicationDependencies.getApplication();
        MutableLiveData<LiveRecipient> liveRecipient = new MutableLiveData<>();

        this.groupDatabase     = DatabaseFactory.getGroupDatabase(context);
        this.recipient         = Transformations.switchMap(liveRecipient, LiveRecipient::getLiveData);
        this.groupRecord       = LiveDataUtil.filterNotNull(LiveDataUtil.mapAsync(recipient, groupRecipient -> groupDatabase.getGroup(groupRecipient.getId()).orNull()));
        this.fullMembers       = mapToFullMembers(this.groupRecord);
        this.requestingMembers = mapToRequestingMembers(this.groupRecord);

        if (groupId.isV2()) {
            LiveData<GroupDatabase.V2GroupProperties> v2Properties = Transformations.map(this.groupRecord, GroupDatabase.GroupRecord::requireV2GroupProperties);
            this.groupLink = Transformations.map(v2Properties, g -> {
                DecryptedGroup               group             = g.getDecryptedGroup();
                AccessControl.AccessRequired addFromInviteLink = group.getAccessControl().getAddFromInviteLink();

                if (group.getInviteLinkPassword().isEmpty()) {
                    return GroupLinkUrlAndStatus.NONE;
                }

                boolean enabled       = addFromInviteLink == AccessControl.AccessRequired.ANY || addFromInviteLink == AccessControl.AccessRequired.ADMINISTRATOR;
                boolean adminApproval = addFromInviteLink == AccessControl.AccessRequired.ADMINISTRATOR;
                String  url           = GroupInviteLinkUrl.forGroup(g.getGroupMasterKey(), group)
                        .getUrl();

                return new GroupLinkUrlAndStatus(enabled, adminApproval, url);
            });
        } else {
            this.groupLink = new MutableLiveData<>(GroupLinkUrlAndStatus.NONE);
        }

        SignalExecutors.BOUNDED.execute(() -> liveRecipient.postValue(Recipient.externalGroup(context, groupId).live()));
    }

    protected static LiveData<List<GroupMemberEntry.FullMember>> mapToFullMembers(@NonNull LiveData<GroupDatabase.GroupRecord> groupRecord) {
        return LiveDataUtil.mapAsync(groupRecord,
                g -> Stream.of(g.getMembers())
                        .map(m -> {
                            Recipient recipient = Recipient.resolved(m);
                            return new GroupMemberEntry.FullMember(recipient, g.isAdmin(recipient));
                        })
                        .sorted(MEMBER_ORDER)
                        .toList());
    }

    protected static LiveData<List<GroupMemberEntry.RequestingMember>> mapToRequestingMembers(@NonNull LiveData<GroupDatabase.GroupRecord> groupRecord) {
        return LiveDataUtil.mapAsync(groupRecord,
                g -> {
                    if (!g.isV2Group()) {
                        return Collections.emptyList();
                    }

                    boolean                         selfAdmin             = g.isAdmin(Recipient.self());
                    List<DecryptedRequestingMember> requestingMembersList = g.requireV2GroupProperties().getDecryptedGroup().getRequestingMembersList();

                    return Stream.of(requestingMembersList)
                            .map(requestingMember -> {
                                Recipient recipient = Recipient.externalPush(ApplicationDependencies.getApplication(), UuidUtil.fromByteString(requestingMember.getUuid()), null, false);
                                return new GroupMemberEntry.RequestingMember(recipient, selfAdmin);
                            })
                            .toList();
                });
    }

    public LiveData<String> getTitle() {
        return LiveDataUtil.combineLatest(groupRecord, recipient, (groupRecord, recipient) -> {
            String title = groupRecord.getTitle();
            if (!TextUtils.isEmpty(title)) {
                return title;
            }
            return recipient.getDisplayName(ApplicationDependencies.getApplication());
        });
    }

    public LiveData<Recipient> getGroupRecipient() {
        return recipient;
    }

    public LiveData<Boolean> isSelfAdmin() {
        return Transformations.map(groupRecord, g -> g.isAdmin(Recipient.self()));
    }

    public LiveData<Boolean> isActive() {
        return Transformations.map(groupRecord, GroupDatabase.GroupRecord::isActive);
    }

    public LiveData<Boolean> getRecipientIsAdmin(@NonNull RecipientId recipientId) {
        return LiveDataUtil.mapAsync(groupRecord, g -> g.isAdmin(Recipient.resolved(recipientId)));
    }

    public LiveData<Integer> getPendingMemberCount() {
        return Transformations.map(groupRecord, g -> g.isV2Group() ? g.requireV2GroupProperties().getDecryptedGroup().getPendingMembersCount() : 0);
    }

    public LiveData<Integer> getPendingAndRequestingMemberCount() {
        return Transformations.map(groupRecord, g -> {
            if (g.isV2Group()) {
                DecryptedGroup decryptedGroup = g.requireV2GroupProperties().getDecryptedGroup();

                return decryptedGroup.getPendingMembersCount() + decryptedGroup.getRequestingMembersCount();
            }
            return 0;
        });
    }

    public LiveData<GroupAccessControl> getMembershipAdditionAccessControl() {
        return Transformations.map(groupRecord, GroupDatabase.GroupRecord::getMembershipAdditionAccessControl);
    }

    public LiveData<GroupAccessControl> getAttributesAccessControl() {
        return Transformations.map(groupRecord, GroupDatabase.GroupRecord::getAttributesAccessControl);
    }

    public LiveData<List<GroupMemberEntry.FullMember>> getNonAdminFullMembers() {
        return Transformations.map(fullMembers,
                members -> Stream.of(members)
                        .filterNot(GroupMemberEntry.FullMember::isAdmin)
                        .toList());
    }

    public LiveData<List<GroupMemberEntry.FullMember>> getFullMembers() {
        return fullMembers;
    }

    public LiveData<List<GroupMemberEntry.RequestingMember>> getRequestingMembers() {
        return requestingMembers;
    }

    public LiveData<Integer> getExpireMessages() {
        return Transformations.map(recipient, Recipient::getExpireMessages);
    }

    public LiveData<Boolean> selfCanEditGroupAttributes() {
        return LiveDataUtil.combineLatest(selfMemberLevel(), getAttributesAccessControl(), LiveGroup::applyAccessControl);
    }

    public LiveData<Boolean> selfCanAddMembers() {
        return LiveDataUtil.combineLatest(selfMemberLevel(), getMembershipAdditionAccessControl(), LiveGroup::applyAccessControl);
    }

    /**
     * A string representing the count of full members and pending members if > 0.
     */
    public LiveData<String> getMembershipCountDescription(@NonNull Resources resources) {
        return LiveDataUtil.combineLatest(getFullMembers(),
                getPendingMemberCount(),
                (fullMembers, invitedCount) -> getMembershipDescription(resources, invitedCount, fullMembers.size()));
    }

    /**
     * A string representing the count of full members.
     */
    public LiveData<String> getFullMembershipCountDescription(@NonNull Resources resources) {
        return Transformations.map(getFullMembers(), fullMembers -> getMembershipDescription(resources, 0, fullMembers.size()));
    }

    private static String getMembershipDescription(@NonNull Resources resources, int invitedCount, int fullMemberCount) {
        return invitedCount > 0 ? resources.getQuantityString(R.plurals.MessageRequestProfileView_members_and_invited, fullMemberCount,
                fullMemberCount, invitedCount)
                : resources.getQuantityString(R.plurals.MessageRequestProfileView_members, fullMemberCount,
                fullMemberCount);
    }

    private LiveData<GroupDatabase.MemberLevel> selfMemberLevel() {
        return Transformations.map(groupRecord, g -> g.memberLevel(Recipient.self()));
    }

    private static boolean applyAccessControl(@NonNull GroupDatabase.MemberLevel memberLevel, @NonNull GroupAccessControl rights) {

        switch (rights) {
            case ALL_MEMBERS: return memberLevel.isInGroup();
            case ONLY_ADMINS: return memberLevel == GroupDatabase.MemberLevel.ADMINISTRATOR;
            case NO_ONE     : return false;
            default:          throw new AssertionError();
        }
    }

    public LiveData<GroupLinkUrlAndStatus> getGroupLink() {
        return groupLink;
    }
}