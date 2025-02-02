package su.sres.securesms.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.securesms.util.Base64;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.storage.SignalContactRecord;
import su.sres.signalservice.internal.storage.protos.ContactRecord.IdentityState;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

class ContactConflictMerger implements StorageSyncHelper.ConflictMerger<SignalContactRecord> {

    private static final String TAG = Log.tag(ContactConflictMerger.class);

    private final Map<UUID, SignalContactRecord>   localByUuid = new HashMap<>();
    private final Map<String, SignalContactRecord> localByE164 = new HashMap<>();

    private final Recipient self;

    ContactConflictMerger(@NonNull Collection<SignalContactRecord> localOnly, @NonNull Recipient self) {
        for (SignalContactRecord contact : localOnly) {
            if (contact.getAddress().getUuid().isPresent()) {
                localByUuid.put(contact.getAddress().getUuid().get(), contact);
            }
            if (contact.getAddress().getNumber().isPresent()) {
                localByE164.put(contact.getAddress().getNumber().get(), contact);
            }
        }

        this.self = self.resolve();
    }

    @Override
    public @NonNull Optional<SignalContactRecord> getMatching(@NonNull SignalContactRecord record) {
        SignalContactRecord localUuid = record.getAddress().getUuid().isPresent()   ? localByUuid.get(record.getAddress().getUuid().get())   : null;
        SignalContactRecord localE164 = record.getAddress().getNumber().isPresent() ? localByE164.get(record.getAddress().getNumber().get()) : null;

        return Optional.fromNullable(localUuid).or(Optional.fromNullable(localE164));
    }

    @Override
    public @NonNull Collection<SignalContactRecord> getInvalidEntries(@NonNull Collection<SignalContactRecord> remoteRecords) {
        Map<String, Set<SignalContactRecord>> localIdToRemoteRecords = new HashMap<>();

        for (SignalContactRecord remote : remoteRecords) {
            Optional<SignalContactRecord> local = getMatching(remote);

            if (local.isPresent()) {
                String                   serializedLocalId = Base64.encodeBytes(local.get().getId().getRaw());
                Set<SignalContactRecord> matches           = localIdToRemoteRecords.get(serializedLocalId);

                if (matches == null) {
                    matches = new HashSet<>();
                }

                matches.add(remote);
                localIdToRemoteRecords.put(serializedLocalId, matches);
            }
        }

        Set<SignalContactRecord> duplicates = new HashSet<>();
        for (Set<SignalContactRecord> matches : localIdToRemoteRecords.values()) {
            if (matches.size() > 1) {
                duplicates.addAll(matches);
            }
        }

        List<SignalContactRecord> selfRecords = Stream.of(remoteRecords)
                .filter(r -> r.getAddress().getUuid().equals(self.getUuid()) || r.getAddress().getNumber().equals(self.getE164()))
                .toList();

        Set<SignalContactRecord> invalid = new HashSet<>();
        invalid.addAll(selfRecords);
        invalid.addAll(duplicates);

        if (invalid.size() > 0) {
            Log.w(TAG, "Found invalid contact entries! Self Records: " + selfRecords.size() + ", Duplicates: " + duplicates.size());
        }

        return invalid;
    }

    @Override
    public @NonNull SignalContactRecord merge(@NonNull SignalContactRecord remote, @NonNull SignalContactRecord local, @NonNull StorageSyncHelper.KeyGenerator keyGenerator) {
        String givenName;
        String familyName;

        if (remote.getGivenName().isPresent() || remote.getFamilyName().isPresent()) {
            givenName  = remote.getGivenName().or("");
            familyName = remote.getFamilyName().or("");
        } else {
            givenName  = local.getGivenName().or("");
            familyName = local.getFamilyName().or("");
        }

        byte[]               unknownFields  = remote.serializeUnknownFields();
        UUID                 uuid           = remote.getAddress().getUuid().or(local.getAddress().getUuid()).orNull();
        String               e164           = remote.getAddress().getNumber().or(local.getAddress().getNumber()).orNull();
        SignalServiceAddress address        = new SignalServiceAddress(uuid, e164);
        byte[]               profileKey     = remote.getProfileKey().or(local.getProfileKey()).orNull();
        String               username       = remote.getUsername().or(local.getUsername()).or("");
        IdentityState        identityState  = remote.getIdentityState();
        byte[]               identityKey    = remote.getIdentityKey().or(local.getIdentityKey()).orNull();
        boolean              blocked        = remote.isBlocked();
        boolean              profileSharing = remote.isProfileSharingEnabled();
        boolean              archived       = remote.isArchived();
        boolean              forcedUnread   = remote.isForcedUnread();
        boolean              matchesRemote  = doParamsMatch(remote, unknownFields, address, givenName, familyName, profileKey, username, identityState, identityKey, blocked, profileSharing, archived, forcedUnread);
        boolean              matchesLocal   = doParamsMatch(local, unknownFields, address, givenName, familyName, profileKey, username, identityState, identityKey, blocked, profileSharing, archived, forcedUnread);

        if (matchesRemote) {
            return remote;
        } else if (matchesLocal) {
            return local;
        } else {
            return new SignalContactRecord.Builder(keyGenerator.generate(), address)
                    .setUnknownFields(unknownFields)
                    .setGivenName(givenName)
                    .setFamilyName(familyName)
                    .setProfileKey(profileKey)
                    .setUsername(username)
                    .setIdentityState(identityState)
                    .setIdentityKey(identityKey)
                    .setBlocked(blocked)
                    .setProfileSharingEnabled(profileSharing)
                    .setForcedUnread(forcedUnread)
                    .build();
        }
    }

    private static boolean doParamsMatch(@NonNull SignalContactRecord contact,
                                         @Nullable byte[] unknownFields,
                                         @NonNull SignalServiceAddress address,
                                         @NonNull String givenName,
                                         @NonNull String familyName,
                                         @Nullable byte[] profileKey,
                                         @NonNull String username,
                                         @Nullable IdentityState identityState,
                                         @Nullable byte[] identityKey,
                                         boolean blocked,
                                         boolean profileSharing,
                                         boolean archived,
                                         boolean forcedUnread)
    {
        return Arrays.equals(contact.serializeUnknownFields(), unknownFields) &&
                Objects.equals(contact.getAddress(), address)                  &&
                Objects.equals(contact.getGivenName().or(""), givenName)       &&
                Objects.equals(contact.getFamilyName().or(""), familyName)     &&
                Arrays.equals(contact.getProfileKey().orNull(), profileKey)    &&
                Objects.equals(contact.getUsername().or(""), username)         &&
                Objects.equals(contact.getIdentityState(), identityState)      &&
                Arrays.equals(contact.getIdentityKey().orNull(), identityKey)  &&
                contact.isBlocked() == blocked                                 &&
                contact.isProfileSharingEnabled() == profileSharing            &&
                contact.isArchived() == archived                               &&
                contact.isForcedUnread() == forcedUnread;
    }
}