package su.sres.securesms.sharing.interstitial;

import android.content.Context;

import androidx.annotation.NonNull;

import su.sres.securesms.R;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.viewholders.RecipientMappingModel;

class ShareInterstitialMappingModel extends RecipientMappingModel<ShareInterstitialMappingModel> {

    private final Recipient recipient;
    private final boolean   isLast;

    ShareInterstitialMappingModel(@NonNull Recipient recipient, boolean isLast) {
        this.recipient = recipient;
        this.isLast    = isLast;
    }

    @Override
    public @NonNull String getName(@NonNull Context context) {
        String name = recipient.isSelf() ? context.getString(R.string.note_to_self)
                : recipient.getShortDisplayNameIncludingUsername(context);

        return isLast ? name : context.getString(R.string.ShareActivity__s_comma, name);
    }

    @Override
    public @NonNull Recipient getRecipient() {
        return recipient;
    }

    @Override
    public boolean areContentsTheSame(@NonNull ShareInterstitialMappingModel newItem) {
        return super.areContentsTheSame(newItem) && isLast == newItem.isLast;
    }
}
