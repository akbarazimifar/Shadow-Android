package su.sres.securesms.util.viewholders;

import android.content.Context;

import androidx.annotation.NonNull;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.MappingModel;

import java.util.Objects;

public abstract class RecipientMappingModel<T extends RecipientMappingModel<T>> implements MappingModel<T> {

    public abstract @NonNull Recipient getRecipient();

    public @NonNull String getName(@NonNull Context context) {
        return getRecipient().getDisplayName(context);
    }

    @Override
    public boolean areItemsTheSame(@NonNull T newItem) {
        return getRecipient().getId().equals(newItem.getRecipient().getId());
    }

    @Override
    public boolean areContentsTheSame(@NonNull T newItem) {
        Context context = ApplicationDependencies.getApplication();
        return getName(context).equals(newItem.getName(context)) && Objects.equals(getRecipient().getContactPhoto(), newItem.getRecipient().getContactPhoto());
    }
}
