/** 
 * Copyright (C) 2011 Whisper Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.securesms.mms;

import android.content.Context;
import android.content.res.Resources.Theme;
import android.net.Uri;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import su.sres.securesms.attachments.Attachment;
import su.sres.securesms.attachments.UriAttachment;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.stickers.StickerLocator;
import su.sres.securesms.util.MediaUtil;
import su.sres.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.security.SecureRandom;

public abstract class Slide {

  protected final Attachment attachment;
  protected final Context    context;

  public Slide(@NonNull Context context, @NonNull Attachment attachment) {
    this.context    = context;
    this.attachment = attachment;

  }

  public String getContentType() {
    return attachment.getContentType();
  }

  @Nullable
  public Uri getUri() {
    return attachment.getDataUri();
  }

  @Nullable
  public Uri getThumbnailUri() {
    return attachment.getThumbnailUri();
  }

  @NonNull
  public Optional<String> getBody() {
    return Optional.absent();
  }

  @NonNull
  public Optional<String> getCaption() {
    return Optional.fromNullable(attachment.getCaption());
  }

  @NonNull
  public Optional<String> getFileName() {
    return Optional.fromNullable(attachment.getFileName());
  }

  @Nullable
  public String getFastPreflightId() {
    return attachment.getFastPreflightId();
  }

  public long getFileSize() {
    return attachment.getSize();
  }

  public boolean hasImage() {
    return false;
  }

  public boolean hasSticker() { return false; }

  public boolean hasVideo() {
    return false;
  }

  public boolean hasAudio() {
    return false;
  }

  public boolean hasDocument() {
    return false;
  }

  public boolean hasLocation() {
    return false;
  }

  public @NonNull String getContentDescription() { return ""; }

  public Attachment asAttachment() {
    return attachment;
  }

  public boolean isInProgress() {
    return attachment.isInProgress();
  }

  public boolean isPendingDownload() {
    return getTransferState() == AttachmentDatabase.TRANSFER_PROGRESS_FAILED ||
           getTransferState() == AttachmentDatabase.TRANSFER_PROGRESS_PENDING;
  }

  public int getTransferState() {
    return attachment.getTransferState();
  }

  public @DrawableRes int getPlaceholderRes(Theme theme) {
    throw new AssertionError("getPlaceholderRes() called for non-drawable slide");
  }

  public boolean hasPlaceholder() {
    return false;
  }

  public boolean hasPlayOverlay() {
    return false;
  }

  protected static Attachment constructAttachmentFromUri(@NonNull  Context        context,
                                                         @NonNull  Uri            uri,
                                                         @NonNull  String         defaultMime,
                                                         long           size,
                                                         int            width,
                                                         int            height,
                                                         boolean        hasThumbnail,
                                                         @Nullable String         fileName,
                                                         @Nullable String         caption,
                                                         @Nullable StickerLocator stickerLocator,
                                                         boolean        voiceNote,
                                                         boolean        quote)
  {
    String                 resolvedType    = Optional.fromNullable(MediaUtil.getMimeType(context, uri)).or(defaultMime);
    String                 fastPreflightId = String.valueOf(new SecureRandom().nextLong());
    return new UriAttachment(uri,
            hasThumbnail ? uri : null,
            resolvedType,
            AttachmentDatabase.TRANSFER_PROGRESS_STARTED,
            size,
            width,
            height,
            fileName,
            fastPreflightId,
            voiceNote,
            quote,
            caption,
            stickerLocator);
  }

  @Override
  public boolean equals(Object other) {
    if (other == null)             return false;
    if (!(other instanceof Slide)) return false;

    Slide that = (Slide)other;

    return Util.equals(this.getContentType(), that.getContentType()) &&
           this.hasAudio() == that.hasAudio()                        &&
           this.hasImage() == that.hasImage()                        &&
           this.hasVideo() == that.hasVideo()                        &&
           this.getTransferState() == that.getTransferState()        &&
           Util.equals(this.getUri(), that.getUri())                 &&
           Util.equals(this.getThumbnailUri(), that.getThumbnailUri());
  }

  @Override
  public int hashCode() {
    return Util.hashCode(getContentType(), hasAudio(), hasImage(),
                         hasVideo(), getUri(), getThumbnailUri(), getTransferState());
  }
}
