package su.sres.securesms.mediasend;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import su.sres.securesms.MediaDocumentsAdapter;
import su.sres.securesms.R;
import su.sres.securesms.components.FromTextView;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.adapter.StableIdGenerator;

import java.util.ArrayList;
import java.util.List;

class CameraContactSelectionAdapter extends RecyclerView.Adapter<CameraContactSelectionAdapter.RecipientViewHolder> {

    private final List<Recipient>           recipients  = new ArrayList<>();
    private final StableIdGenerator<String> idGenerator = new StableIdGenerator<>();

    CameraContactSelectionAdapter() {
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return idGenerator.getId(recipients.get(position).getId().serialize());
    }

    @Override
    public @NonNull RecipientViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new RecipientViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.camera_contact_selection_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecipientViewHolder holder, int position) {
        holder.bind(recipients.get(position), position == recipients.size() - 1);
    }

    @Override
    public int getItemCount() {
        return recipients.size();
    }

    void setRecipients(@NonNull List<Recipient> recipients) {
        this.recipients.clear();
        this.recipients.addAll(recipients);
        notifyDataSetChanged();
    }

    static class RecipientViewHolder extends MediaDocumentsAdapter.ViewHolder {

        private final FromTextView name;

        RecipientViewHolder(View itemView) {
            super(itemView);
            name = (FromTextView) itemView;
        }

        void bind(@NonNull Recipient recipient, boolean isLast) {
            name.setText(recipient, true, isLast ? null : ",");
        }
    }
}