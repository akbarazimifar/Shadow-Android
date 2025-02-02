package su.sres.securesms.groups.ui.invitesandrequests.joining;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import su.sres.storageservice.protos.groups.AccessControl;
import su.sres.storageservice.protos.groups.local.DecryptedGroupJoinInfo;

public final class GroupDetails {
    private final DecryptedGroupJoinInfo joinInfo;
    private final byte[]                 avatarBytes;

    public GroupDetails(@NonNull DecryptedGroupJoinInfo joinInfo,
                        @Nullable byte[] avatarBytes)
    {
        this.joinInfo    = joinInfo;
        this.avatarBytes = avatarBytes;
    }

    public @NonNull String getGroupName() {
        return joinInfo.getTitle();
    }

    public @Nullable byte[] getAvatarBytes() {
        return avatarBytes;
    }

    public @NonNull DecryptedGroupJoinInfo getJoinInfo() {
        return joinInfo;
    }

    public int getGroupMembershipCount() {
        return joinInfo.getMemberCount();
    }

    public boolean joinRequiresAdminApproval() {
        return joinInfo.getAddFromInviteLink() == AccessControl.AccessRequired.ADMINISTRATOR;
    }
}