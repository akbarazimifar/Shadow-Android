package su.sres.securesms.sharing;

import android.content.Context;

import androidx.annotation.NonNull;

import su.sres.securesms.R;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.MappingModel;

class ShareSelectionMappingModel implements MappingModel<ShareSelectionMappingModel> {

    private final ShareContact shareContact;
    private final boolean      isLast;

    ShareSelectionMappingModel(@NonNull ShareContact shareContact, boolean isLast) {
        this.shareContact = shareContact;
        this.isLast       = isLast;
    }

    @NonNull String getName(@NonNull Context context) {
        String name = shareContact.getRecipientId()
                .transform(Recipient::resolved)
                .transform(recipient -> recipient.isSelf() ? context.getString(R.string.note_to_self)
                        : recipient.getShortDisplayNameIncludingUsername(context))
                .or(shareContact.getNumber());

        return isLast ? name : context.getString(R.string.ShareActivity__s_comma, name);
    }

    @Override
    public boolean areItemsTheSame(@NonNull ShareSelectionMappingModel newItem) {
        return newItem.shareContact.equals(shareContact);
    }

    @Override
    public boolean areContentsTheSame(@NonNull ShareSelectionMappingModel newItem) {
        return areItemsTheSame(newItem) && newItem.isLast == isLast;
    }
}
