package su.sres.securesms.components.webrtc.participantslist;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import su.sres.securesms.R;
import su.sres.securesms.events.CallParticipant;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.viewholders.RecipientMappingModel;

public final class CallParticipantViewState extends RecipientMappingModel<CallParticipantViewState> {

    private final CallParticipant callParticipant;

    CallParticipantViewState(@NonNull CallParticipant callParticipant) {
        this.callParticipant = callParticipant;
    }

    @Override
    public @NonNull Recipient getRecipient() {
        return callParticipant.getRecipient();
    }

    @Override
    public @NonNull String getName(@NonNull Context context) {
        return callParticipant.getRecipientDisplayName(context);
    }

    public int getVideoMutedVisibility() {
        return callParticipant.isVideoEnabled() ? View.GONE : View.VISIBLE;
    }

    public int getAudioMutedVisibility() {
        return callParticipant.isMicrophoneEnabled() ? View.GONE : View.VISIBLE;
    }

    @Override
    public boolean areItemsTheSame(@NonNull CallParticipantViewState newItem) {
        return callParticipant.getCallParticipantId().equals(newItem.callParticipant.getCallParticipantId());
    }

    @Override
    public boolean areContentsTheSame(@NonNull CallParticipantViewState newItem) {
        return super.areContentsTheSame(newItem)                                            &&
                callParticipant.isVideoEnabled() == newItem.callParticipant.isVideoEnabled() &&
                callParticipant.isMicrophoneEnabled() == newItem.callParticipant.isMicrophoneEnabled();
    }
}
