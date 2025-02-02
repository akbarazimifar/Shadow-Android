package su.sres.securesms.jobs;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.os.Build;
import android.text.TextUtils;

import com.annimon.stream.Stream;

import org.greenrobot.eventbus.EventBus;
import org.signal.libsignal.metadata.certificate.InvalidCertificateException;
import org.signal.libsignal.metadata.certificate.SenderCertificate;
import su.sres.securesms.TextSecureExpiredException;
import su.sres.securesms.attachments.Attachment;
import su.sres.securesms.attachments.DatabaseAttachment;
import su.sres.securesms.blurhash.BlurHash;
import su.sres.securesms.contactshare.Contact;
import su.sres.securesms.contactshare.ContactModelMapper;
import su.sres.securesms.crypto.ProfileKeyUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.model.Mention;
import su.sres.securesms.database.model.StickerRecord;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.events.PartProgressEvent;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.impl.BackoffUtil;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.CertificateType;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.linkpreview.LinkPreview;
import su.sres.core.util.logging.Log;
import su.sres.securesms.mms.DecryptableStreamUriLoader;
import su.sres.securesms.mms.OutgoingMediaMessage;
import su.sres.securesms.mms.PartAuthority;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.transport.RetryLaterException;
import su.sres.securesms.util.Base64;
import su.sres.securesms.util.BitmapDecodingException;
import su.sres.securesms.util.BitmapUtil;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.Hex;
import su.sres.securesms.util.MediaUtil;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.crypto.UnidentifiedAccessPair;
import su.sres.signalservice.api.messages.SignalServiceAttachment;
import su.sres.signalservice.api.messages.SignalServiceAttachmentPointer;
import su.sres.signalservice.api.messages.SignalServiceAttachmentRemoteId;
import su.sres.signalservice.api.messages.SignalServiceDataMessage;
import su.sres.signalservice.api.messages.SignalServiceDataMessage.Preview;
import su.sres.signalservice.api.messages.multidevice.SentTranscriptMessage;
import su.sres.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import su.sres.signalservice.api.messages.shared.SharedContact;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import su.sres.signalservice.api.push.exceptions.ServerRejectedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public abstract class PushSendJob extends SendJob {

    private static final String TAG                           = PushSendJob.class.getSimpleName();
    private static final long   CERTIFICATE_EXPIRATION_BUFFER = TimeUnit.DAYS.toMillis(1);

  protected PushSendJob(Job.Parameters parameters) {
    super(parameters);
  }

  protected static Job.Parameters constructParameters(@NonNull Recipient recipient, boolean hasMedia) {
      return new Parameters.Builder()
              .setQueue(recipient.getId().toQueueKey(hasMedia))
              .addConstraint(NetworkConstraint.KEY)
              .setLifespan(TimeUnit.DAYS.toMillis(1))
              .setMaxAttempts(Parameters.UNLIMITED)
              .build();
  }

  @Override
  protected final void onSend() throws Exception {
    if (TextSecurePreferences.getSignedPreKeyFailureCount(context) > 5) {
      ApplicationDependencies.getJobManager().add(new RotateSignedPreKeyJob());

      throw new TextSecureExpiredException("Too many signed prekey rotation failures");
    }

    onPushSend();
  }

  @Override
  public void onRetry() {
    super.onRetry();
    Log.i(TAG, "onRetry()");

      if (getRunAttempt() > 1) {
      Log.i(TAG, "Scheduling service outage detection job.");
        ApplicationDependencies.getJobManager().add(new ServiceOutageDetectionJob());
    }
  }

  @Override
  protected boolean shouldTrace() {
    return true;
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    if (exception instanceof ServerRejectedException) {
      return false;
    }

    return exception instanceof IOException ||
            exception instanceof RetryLaterException;
  }

  @Override
  public long getNextRunAttemptBackoff(int pastAttemptCount, @NonNull Exception exception) {
    if (exception instanceof NonSuccessfulResponseCodeException) {
      if (((NonSuccessfulResponseCodeException) exception).is5xx()) {
        return BackoffUtil.exponentialBackoff(pastAttemptCount, FeatureFlags.getServerErrorMaxBackoff());
      }
    }

    return super.getNextRunAttemptBackoff(pastAttemptCount, exception);
  }

  protected Optional<byte[]> getProfileKey(@NonNull Recipient recipient) {
    if (!recipient.resolve().isSystemContact() && !recipient.resolve().isProfileSharing()) {
      return Optional.absent();
    }

    return Optional.of(ProfileKeyUtil.getProfileKey(context));
  }

  protected SignalServiceAttachment getAttachmentFor(Attachment attachment) {
    try {
      if (attachment.getUri() == null || attachment.getSize() == 0) throw new IOException("Assertion failed, outgoing attachment has no data!");
      InputStream is = PartAuthority.getAttachmentStream(context, attachment.getUri());
      return SignalServiceAttachment.newStreamBuilder()
                                    .withStream(is)
                                    .withContentType(attachment.getContentType())
                                    .withLength(attachment.getSize())
                                    .withFileName(attachment.getFileName())
                                    .withVoiceNote(attachment.isVoiceNote())
                                    .withBorderless(attachment.isBorderless())
                                    .withWidth(attachment.getWidth())
                                    .withHeight(attachment.getHeight())
                                    .withCaption(attachment.getCaption())
              .withListener((total, progress) -> EventBus.getDefault().postSticky(new PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, total, progress)))
                                    .build();
    } catch (IOException ioe) {
      Log.w(TAG, "Couldn't open attachment", ioe);
    }
    return null;
  }

  protected static Set<String> enqueueCompressingAndUploadAttachmentsChains(@NonNull JobManager jobManager, OutgoingMediaMessage message) {
    List<Attachment> attachments = new LinkedList<>();

    attachments.addAll(message.getAttachments());

    attachments.addAll(Stream.of(message.getLinkPreviews())
            .map(LinkPreview::getThumbnail)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList());

    attachments.addAll(Stream.of(message.getSharedContacts())
            .map(Contact::getAvatar).withoutNulls()
            .map(Contact.Avatar::getAttachment).withoutNulls()
            .toList());

    return new HashSet<>(Stream.of(attachments).map(a -> {
      AttachmentUploadJob attachmentUploadJob = new AttachmentUploadJob(((DatabaseAttachment) a).getAttachmentId());

      if (message.isGroup()) {
        jobManager.startChain(AttachmentCompressionJob.fromAttachment((DatabaseAttachment) a, false, -1))
                .then(attachmentUploadJob)
                .enqueue();
      } else {
        jobManager.startChain(AttachmentCompressionJob.fromAttachment((DatabaseAttachment) a, false, -1))
                .then(new ResumableUploadSpecJob())
                .then(attachmentUploadJob)
                .enqueue();
      }

      return attachmentUploadJob.getId();
    })
            .toList());
  }

  protected @NonNull List<SignalServiceAttachment> getAttachmentPointersFor(List<Attachment> attachments) {
    return Stream.of(attachments).map(this::getAttachmentPointerFor).filter(a -> a != null).toList();
  }

  protected @Nullable SignalServiceAttachment getAttachmentPointerFor(Attachment attachment) {
    if (TextUtils.isEmpty(attachment.getLocation())) {
      Log.w(TAG, "empty content id");
      return null;
    }

    if (TextUtils.isEmpty(attachment.getKey())) {
      Log.w(TAG, "empty encrypted key");
      return null;
    }

    try {
      final SignalServiceAttachmentRemoteId remoteId = SignalServiceAttachmentRemoteId.from(attachment.getLocation());
      final byte[]                          key      = Base64.decode(attachment.getKey());

      int width  = attachment.getWidth();
      int height = attachment.getHeight();

      if ((width == 0 || height == 0) && MediaUtil.hasVideoThumbnail(context, attachment.getUri())) {
        Bitmap thumbnail = MediaUtil.getVideoThumbnail(context, attachment.getUri(), 1000);

        if (thumbnail != null) {
          width  = thumbnail.getWidth();
          height = thumbnail.getHeight();
        }
      }

      return new SignalServiceAttachmentPointer(attachment.getCdnNumber(),
              remoteId,
              attachment.getContentType(),
              key,
              Optional.of(Util.toIntExact(attachment.getSize())),
              Optional.absent(),
              width,
              height,
              Optional.fromNullable(attachment.getDigest()),
              Optional.fromNullable(attachment.getFileName()),
              attachment.isVoiceNote(),
              attachment.isBorderless(),
              Optional.fromNullable(attachment.getCaption()),
              Optional.fromNullable(attachment.getBlurHash()).transform(BlurHash::getHash),
              attachment.getUploadTimestamp());
    } catch (IOException | ArithmeticException e) {
      Log.w(TAG, e);
      return null;
    }
  }

  protected static void notifyMediaMessageDeliveryFailed(Context context, long messageId) {
    long      threadId  = DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    Recipient recipient = DatabaseFactory.getThreadDatabase(context).getRecipientForThreadId(threadId);

    if (threadId != -1 && recipient != null) {
      ApplicationDependencies.getMessageNotifier().notifyMessageDeliveryFailed(context, recipient, threadId);
    }
  }

  protected Optional<SignalServiceDataMessage.Quote> getQuoteFor(OutgoingMediaMessage message) throws IOException {
    if (message.getOutgoingQuote() == null) return Optional.absent();

    long                                                  quoteId             = message.getOutgoingQuote().getId();
    String                                                quoteBody           = message.getOutgoingQuote().getText();
    RecipientId                                           quoteAuthor         = message.getOutgoingQuote().getAuthor();
    List<SignalServiceDataMessage.Mention>                quoteMentions       = getMentionsFor(message.getOutgoingQuote().getMentions());
    List<SignalServiceDataMessage.Quote.QuotedAttachment> quoteAttachments    = new LinkedList<>();
    List<Attachment>                                      filteredAttachments = Stream.of(message.getOutgoingQuote().getAttachments())
            .filterNot(a -> MediaUtil.isViewOnceType(a.getContentType()))
            .toList();

    for (Attachment attachment : filteredAttachments) {
      BitmapUtil.ScaleResult  thumbnailData = null;
      SignalServiceAttachment thumbnail     = null;
      String                  thumbnailType = MediaUtil.IMAGE_JPEG;

      try {
        if (MediaUtil.isImageType(attachment.getContentType()) && attachment.getUri() != null) {
          Bitmap.CompressFormat format = BitmapUtil.getCompressFormatForContentType(attachment.getContentType());

          thumbnailData = BitmapUtil.createScaledBytes(context, new DecryptableStreamUriLoader.DecryptableUri(attachment.getUri()), 100, 100, 500 * 1024, format);
          thumbnailType = attachment.getContentType();
        } else if (Build.VERSION.SDK_INT >= 23 && MediaUtil.isVideoType(attachment.getContentType()) && attachment.getUri() != null) {
          Bitmap bitmap = MediaUtil.getVideoThumbnail(context, attachment.getUri(), 1000);

          if (bitmap != null) {
            thumbnailData = BitmapUtil.createScaledBytes(context, bitmap, 100, 100, 500 * 1024);
          }
        }

        if (thumbnailData != null) {
          SignalServiceAttachment.Builder builder = SignalServiceAttachment.newStreamBuilder()
                  .withContentType(thumbnailType)
                  .withWidth(thumbnailData.getWidth())
                  .withHeight(thumbnailData.getHeight())
                  .withLength(thumbnailData.getBitmap().length)
                  .withStream(new ByteArrayInputStream(thumbnailData.getBitmap()));

          if (FeatureFlags.attachmentsV3()) {
            builder.withResumableUploadSpec(ApplicationDependencies.getSignalServiceMessageSender().getResumableUploadSpec());
          }

          thumbnail = builder.build();
        }

        quoteAttachments.add(new SignalServiceDataMessage.Quote.QuotedAttachment(attachment.getContentType(),
                                                                                 attachment.getFileName(),
                                                                                 thumbnail));
      } catch (BitmapDecodingException e) {
        Log.w(TAG, e);
      }
    }

    Recipient            quoteAuthorRecipient = Recipient.resolved(quoteAuthor);
    SignalServiceAddress quoteAddress         = RecipientUtil.toSignalServiceAddress(context, quoteAuthorRecipient);
    return Optional.of(new SignalServiceDataMessage.Quote(quoteId, quoteAddress, quoteBody, quoteAttachments, quoteMentions));
  }

  protected Optional<SignalServiceDataMessage.Sticker> getStickerFor(OutgoingMediaMessage message) {
    Attachment stickerAttachment = Stream.of(message.getAttachments()).filter(Attachment::isSticker).findFirst().orElse(null);

    if (stickerAttachment == null) {
      return Optional.absent();
    }

    try {
      byte[]                  packId     = Hex.fromStringCondensed(stickerAttachment.getSticker().getPackId());
      byte[]                  packKey    = Hex.fromStringCondensed(stickerAttachment.getSticker().getPackKey());
      int                     stickerId  = stickerAttachment.getSticker().getStickerId();
      StickerRecord record     = DatabaseFactory.getStickerDatabase(context).getSticker(stickerAttachment.getSticker().getPackId(), stickerId, false);
      String                  emoji      = record != null ? record.getEmoji() : null;
      SignalServiceAttachment attachment = getAttachmentPointerFor(stickerAttachment);

      return Optional.of(new SignalServiceDataMessage.Sticker(packId, packKey, stickerId, emoji, attachment));
    } catch (IOException e) {
      Log.w(TAG, "Failed to decode sticker id/key", e);
      return Optional.absent();
    }
  }

  List<SharedContact> getSharedContactsFor(OutgoingMediaMessage mediaMessage) {
    List<SharedContact> sharedContacts = new LinkedList<>();

    for (Contact contact : mediaMessage.getSharedContacts()) {
      SharedContact.Builder builder = ContactModelMapper.localToRemoteBuilder(contact);
      SharedContact.Avatar  avatar  = null;

      if (contact.getAvatar() != null && contact.getAvatar().getAttachment() != null) {
        avatar = SharedContact.Avatar.newBuilder().withAttachment(getAttachmentFor(contact.getAvatarAttachment()))
                                                  .withProfileFlag(contact.getAvatar().isProfile())
                                                  .build();
      }

      builder.setAvatar(avatar);
      sharedContacts.add(builder.build());
    }

    return sharedContacts;
  }

  List<Preview> getPreviewsFor(OutgoingMediaMessage mediaMessage) {
    return Stream.of(mediaMessage.getLinkPreviews()).map(lp -> {
      SignalServiceAttachment attachment = lp.getThumbnail().isPresent() ? getAttachmentPointerFor(lp.getThumbnail().get()) : null;
      return new Preview(lp.getUrl(), lp.getTitle(), lp.getDescription(), lp.getDate(), Optional.fromNullable(attachment));
    }).toList();
  }

  List<SignalServiceDataMessage.Mention> getMentionsFor(@NonNull List<Mention> mentions) {
    return Stream.of(mentions)
            .map(m -> new SignalServiceDataMessage.Mention(Recipient.resolved(m.getRecipientId()).requireUuid(), m.getStart(), m.getLength()))
            .toList();
  }

  protected void rotateSenderCertificateIfNecessary() throws IOException {
        try {
          Collection<CertificateType> requiredCertificateTypes = SignalStore.userLoginPrivacy()
                  .getRequiredCertificateTypes();

          Log.i(TAG, "Ensuring we have these certificates " + requiredCertificateTypes);

          for (CertificateType certificateType : requiredCertificateTypes) {

            byte[] certificateBytes = SignalStore.certificateValues()
                    .getUnidentifiedAccessCertificate(certificateType);

            if (certificateBytes == null) {
              throw new InvalidCertificateException(String.format("No certificate %s was present.", certificateType));
            }

            SenderCertificate certificate = new SenderCertificate(certificateBytes);

            if (System.currentTimeMillis() > (certificate.getExpiration() - CERTIFICATE_EXPIRATION_BUFFER)) {
              throw new InvalidCertificateException(String.format(Locale.US, "Certificate %s is expired, or close to it. Expires on: %d, currently: %d", certificateType, certificate.getExpiration(), System.currentTimeMillis()));
            }
            Log.d(TAG, String.format("Certificate %s is valid", certificateType));
            }

          Log.d(TAG, "All certificates are valid.");
        } catch (InvalidCertificateException e) {
          Log.w(TAG, "A certificate was invalid at send time. Fetching new ones.", e);
          if (!ApplicationDependencies.getJobManager().runSynchronously(new RotateCertificateJob(), 5000).isPresent()) {
            throw new IOException("Timeout rotating certificate");
          }
        }
    }

  protected SignalServiceSyncMessage buildSelfSendSyncMessage(@NonNull Context context, @NonNull SignalServiceDataMessage message, Optional<UnidentifiedAccessPair> syncAccess) {
    SignalServiceAddress  localAddress = new SignalServiceAddress(TextSecurePreferences.getLocalUuid(context), TextSecurePreferences.getLocalNumber(context));
    SentTranscriptMessage transcript   = new SentTranscriptMessage(Optional.of(localAddress),
            message.getTimestamp(),
            message,
            message.getExpiresInSeconds(),
            Collections.singletonMap(localAddress, syncAccess.isPresent()),
            false);
    return SignalServiceSyncMessage.forSentTranscript(transcript);
  }

  protected abstract void onPushSend() throws Exception;
}
