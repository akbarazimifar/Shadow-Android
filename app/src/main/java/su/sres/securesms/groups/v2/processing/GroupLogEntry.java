package su.sres.securesms.groups.v2.processing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import su.sres.storageservice.protos.groups.local.DecryptedGroup;
import su.sres.storageservice.protos.groups.local.DecryptedGroupChange;

/**
 * Pair of a group state and optionally the corresponding change.
 * <p>
 * Changes are typically not available for pending members.
 */
final class GroupLogEntry {

    @NonNull  private final DecryptedGroup       group;
    @Nullable private final DecryptedGroupChange change;

    GroupLogEntry(@NonNull DecryptedGroup group, @Nullable DecryptedGroupChange change) {
        if (change != null && group.getRevision() != change.getRevision()) {
            throw new AssertionError();
        }

        this.group  = group;
        this.change = change;
    }

    @NonNull DecryptedGroup getGroup() {
        return group;
    }

    @Nullable DecryptedGroupChange getChange() {
        return change;
    }
}