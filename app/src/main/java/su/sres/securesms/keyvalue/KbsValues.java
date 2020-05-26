package su.sres.securesms.keyvalue;

// import androidx.annotation.Nullable;

// import su.sres.securesms.util.JsonUtils;
// import su.sres.signalservice.api.RegistrationLockData;
// import su.sres.signalservice.internal.contacts.entities.TokenResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.SecureRandom;

import su.sres.signalservice.api.kbs.MasterKey;

public final class KbsValues {

//    public static final String V2_LOCK_ENABLED     = "kbs.v2_lock_enabled";
    private static final String MASTER_KEY          = "kbs.registration_lock_master_key";
//    private static final String TOKEN_RESPONSE      = "kbs.token_response";
//    private static final String LOCK_LOCAL_PIN_HASH = "kbs.registration_lock_local_pin_hash";

    private final KeyValueStore store;

    KbsValues(KeyValueStore store) {
        this.store = store;
    }

    /**
     * Deliberately does not clear the {@link #MASTER_KEY}.
     *
     * Should only be called by {@link su.sres.securesms.pin.PinState}
     */
//    public void clearRegistrationLockAndPin() {
//        store.beginWrite()
//                .remove(V2_LOCK_ENABLED)
//                .remove(TOKEN_RESPONSE)
//                .remove(LOCK_LOCAL_PIN_HASH)
//                .commit();
//    }

    /** Should only be set by {@link su.sres.securesms.pin.PinState}. */
//    public synchronized void setKbsMasterKey(@NonNull KbsPinData pinData, @NonNull String localPinHash) {

        public synchronized void setKbsMasterKey(MasterKey masterKey) {
//            MasterKey masterKey     = registrationLockData.getMasterKey();
//            String    tokenResponse;
//            try {
//                tokenResponse = JsonUtils.toJson(pinData.getTokenResponse());
//            } catch (IOException e) {
//                throw new AssertionError(e);

            store.beginWrite()
//                    .putString(TOKEN_RESPONSE, tokenResponse)
                    .putBlob(MASTER_KEY, masterKey.serialize())
//                    .putString(LOCK_LOCAL_PIN_HASH, localPinHash)
                    .commit();
    }

/*  // Should only be set by {@link org.thoughtcrime.securesms.pin.PinState}.
public synchronized void setV2RegistrationLockEnabled(boolean enabled) {
        store.beginWrite().putBoolean(V2_LOCK_ENABLED, enabled).apply();
    }

    public synchronized boolean isV2RegistrationLockEnabled() {
        return store.getBoolean(V2_LOCK_ENABLED, false);
    } */

        /**
         * Finds or creates the master key. Therefore this will always return a master key whether backed
         * up or not.
         * <p>
         * If you only want a key when it's backed up, use {@link #getPinBackedMasterKey()}.
         */
        public synchronized @NonNull MasterKey getOrCreateMasterKey() {
            byte[] blob = store.getBlob(MASTER_KEY, null);

            if (blob == null) {
                store.beginWrite()
                        .putBlob(MASTER_KEY, MasterKey.createNew(new SecureRandom()).serialize())
                        .commit();
                blob = store.getBlob(MASTER_KEY, null);
    }

            return new MasterKey(blob);
        }

    /**
     * Returns null if master key is not backed up by a pin.
     */
//    public synchronized @Nullable MasterKey getPinBackedMasterKey() {
//        if (!isV2RegistrationLockEnabled()) return null;
//        return getMasterKey();
//    }

    private synchronized @Nullable MasterKey getMasterKey() {
        byte[] blob = store.getBlob(MASTER_KEY, null);
        return blob != null ? new MasterKey(blob) : null;
    }

//    public @Nullable String getRegistrationLockToken() {
//        MasterKey masterKey = getPinBackedMasterKey();
//    if (masterKey == null) {
//      return null;
//    } else {
//      return masterKey.deriveRegistrationLock();
//    }
//    }

//    public synchronized @Nullable String getLocalPinHash() {
//    return store.getString(LOCK_LOCAL_PIN_HASH, null);
//    }

//    public synchronized boolean hasPin() {
//    return getLocalPinHash() != null;
//    }

//    public synchronized @Nullable TokenResponse getRegistrationLockTokenResponse() {
//    String token = store.getString(TOKEN_RESPONSE, null);
//
//        if (token == null) return null;

//        try {
//            return JsonUtils.fromJson(token, TokenResponse.class);
//        } catch (IOException e) {
//            throw new AssertionError(e);
//        }
 //   }

}