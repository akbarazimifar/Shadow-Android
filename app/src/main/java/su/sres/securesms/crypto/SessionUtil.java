package su.sres.securesms.crypto;

import android.content.Context;
import androidx.annotation.NonNull;

import su.sres.securesms.crypto.storage.TextSecureSessionStore;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionStore;
import su.sres.signalservice.api.push.SignalServiceAddress;

public class SessionUtil {

  public static boolean hasSession(@NonNull Context context, @NonNull RecipientId id) {
    SessionStore          sessionStore   = new TextSecureSessionStore(context);
    SignalProtocolAddress axolotlAddress = new SignalProtocolAddress(Recipient.resolved(id).requireServiceId(), SignalServiceAddress.DEFAULT_DEVICE_ID);

    return sessionStore.containsSession(axolotlAddress);
  }

  public static void archiveSiblingSessions(Context context, SignalProtocolAddress address) {
    TextSecureSessionStore  sessionStore = new TextSecureSessionStore(context);
    sessionStore.archiveSiblingSessions(address);
  }

  public static void archiveAllSessions(Context context) {
    new TextSecureSessionStore(context).archiveAllSessions();
  }

  public static void archiveSession(Context context, RecipientId recipientId, int deviceId) {
    new TextSecureSessionStore(context).archiveSession(recipientId, deviceId);
  }

}
