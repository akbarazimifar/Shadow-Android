package su.sres.securesms.groups.ui.invitesandrequests.joining;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProviders;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import su.sres.securesms.R;
import su.sres.securesms.color.MaterialColor;
import su.sres.securesms.components.AvatarImageView;
import su.sres.securesms.contacts.avatars.FallbackContactPhoto;
import su.sres.securesms.contacts.avatars.ResourceContactPhoto;
import su.sres.securesms.conversation.ConversationActivity;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.v2.GroupInviteLinkUrl;
import su.sres.securesms.jobs.RetrieveProfileJob;
import su.sres.securesms.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.BottomSheetUtil;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.PlayStoreUtil;
import su.sres.securesms.util.ThemeUtil;

public final class GroupJoinBottomSheetDialogFragment extends BottomSheetDialogFragment {

    private static final String TAG = Log.tag(GroupJoinUpdateRequiredBottomSheetDialogFragment.class);

    private static final String ARG_GROUP_INVITE_LINK_URL = "group_invite_url";

    private ProgressBar     busy;
    private AvatarImageView avatar;
    private TextView        groupName;
    private TextView        groupDetails;
    private TextView        groupJoinExplain;
    private Button          groupJoinButton;
    private Button          groupCancelButton;

    public static void show(@NonNull FragmentManager manager,
                            @NonNull GroupInviteLinkUrl groupInviteLinkUrl)
    {
        GroupJoinBottomSheetDialogFragment fragment = new GroupJoinBottomSheetDialogFragment();

        Bundle args = new Bundle();
        args.putString(ARG_GROUP_INVITE_LINK_URL, groupInviteLinkUrl.getUrl());
        fragment.setArguments(args);

        fragment.show(manager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(DialogFragment.STYLE_NORMAL,
                ThemeUtil.isDarkTheme(requireContext()) ? R.style.Theme_Signal_RoundedBottomSheet
                        : R.style.Theme_Signal_RoundedBottomSheet_Light);

        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.group_join_bottom_sheet, container, false);

        groupCancelButton = view.findViewById(R.id.group_join_cancel_button);
        groupJoinButton   = view.findViewById(R.id.group_join_button);
        busy              = view.findViewById(R.id.group_join_busy);
        avatar            = view.findViewById(R.id.group_join_recipient_avatar);
        groupName         = view.findViewById(R.id.group_join_group_name);
        groupDetails      = view.findViewById(R.id.group_join_group_details);
        groupJoinExplain  = view.findViewById(R.id.group_join_explain);

        groupCancelButton.setOnClickListener(v -> dismiss());

        avatar.setImageBytesForGroup(null, new FallbackPhotoProvider(), MaterialColor.STEEL);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        GroupJoinViewModel.Factory factory = new GroupJoinViewModel.Factory(requireContext().getApplicationContext(), getGroupInviteLinkUrl());

        GroupJoinViewModel viewModel = ViewModelProviders.of(this, factory).get(GroupJoinViewModel.class);

        viewModel.getGroupDetails().observe(getViewLifecycleOwner(), details -> {
            groupName.setText(details.getGroupName());
            groupDetails.setText(requireContext().getResources().getQuantityString(R.plurals.GroupJoinBottomSheetDialogFragment_group_dot_d_members, details.getGroupMembershipCount(), details.getGroupMembershipCount()));
            switch (getGroupJoinStatus()) {
                case COMING_SOON:
                    groupJoinExplain.setText(R.string.GroupJoinUpdateRequiredBottomSheetDialogFragment_coming_soon);
                    groupCancelButton.setText(android.R.string.ok);
                    groupJoinButton.setVisibility(View.GONE);
                    break;
                case UPDATE_TO_JOIN:
                    groupJoinExplain.setText(R.string.GroupJoinUpdateRequiredBottomSheetDialogFragment_update_message);
                    groupJoinButton.setText(R.string.GroupJoinUpdateRequiredBottomSheetDialogFragment_update_signal);
                    groupJoinButton.setOnClickListener(v -> {
                        PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext());
                        dismiss();
                    });
                    groupJoinButton.setVisibility(View.VISIBLE);
                    break;
                case UPDATE_LINKED_DEVICE_TO_JOIN:
                    groupJoinExplain.setText(R.string.GroupJoinUpdateRequiredBottomSheetDialogFragment_update_linked_device_message);
                    groupCancelButton.setText(android.R.string.ok);
                    groupJoinButton.setVisibility(View.GONE);
                    ApplicationDependencies.getJobManager()
                            .add(RetrieveProfileJob.forRecipient(Recipient.self().getId()));
                    break;
                case LOCAL_CAN_JOIN:
                    groupJoinExplain.setText(details.joinRequiresAdminApproval() ? R.string.GroupJoinBottomSheetDialogFragment_admin_approval_needed
                            : R.string.GroupJoinBottomSheetDialogFragment_direct_join);
                    groupJoinButton.setText(details.joinRequiresAdminApproval() ? R.string.GroupJoinBottomSheetDialogFragment_request_to_join
                            : R.string.GroupJoinBottomSheetDialogFragment_join);
                    groupJoinButton.setOnClickListener(v -> {
                        Log.i(TAG, details.joinRequiresAdminApproval() ? "Attempting to direct join group" : "Attempting to request to join group");
                        viewModel.join(details);
                    });
                    groupJoinButton.setVisibility(View.VISIBLE);
                    break;
            }

            avatar.setImageBytesForGroup(details.getAvatarBytes(), new FallbackPhotoProvider(), MaterialColor.STEEL);

            groupCancelButton.setVisibility(View.VISIBLE);
        });

        viewModel.isBusy().observe(getViewLifecycleOwner(), isBusy -> busy.setVisibility(isBusy ? View.VISIBLE : View.GONE));
        viewModel.getErrors().observe(getViewLifecycleOwner(), error -> {
            Toast.makeText(requireContext(), errorToMessage(error), Toast.LENGTH_SHORT).show();
            dismiss();
        });

        viewModel.getJoinErrors().observe(getViewLifecycleOwner(), error -> Toast.makeText(requireContext(), errorToMessage(error), Toast.LENGTH_SHORT).show());

        viewModel.getJoinSuccess().observe(getViewLifecycleOwner(), joinGroupSuccess -> {
                    Log.i(TAG, "Group joined, navigating to group");

                    Intent intent = ConversationActivity.buildIntent(requireContext(), joinGroupSuccess.getGroupRecipient().getId(), joinGroupSuccess.getGroupThreadId());
                    requireActivity().startActivity(intent);

                    dismiss();
                }
        );
    }

    private static ExtendedGroupJoinStatus getGroupJoinStatus() {
        FeatureFlags.GroupJoinStatus groupJoinStatus = FeatureFlags.clientLocalGroupJoinStatus();

        switch (groupJoinStatus) {
            case COMING_SOON   : return ExtendedGroupJoinStatus.COMING_SOON;
            case UPDATE_TO_JOIN: return ExtendedGroupJoinStatus.UPDATE_TO_JOIN;
            case LOCAL_CAN_JOIN: {
                if (Recipient.self().getGroupsV2Capability() != Recipient.Capability.SUPPORTED) {
                    return ExtendedGroupJoinStatus.UPDATE_LINKED_DEVICE_TO_JOIN;
                }

                return ExtendedGroupJoinStatus.LOCAL_CAN_JOIN;
            }

            default: throw new AssertionError();
        }
    }

    private @NonNull String errorToMessage(@NonNull FetchGroupDetailsError error) {
        if (error == FetchGroupDetailsError.GroupLinkNotActive) {
            return getString(R.string.GroupJoinBottomSheetDialogFragment_this_group_link_is_not_active);
        }
        return getString(R.string.GroupJoinBottomSheetDialogFragment_unable_to_get_group_information_please_try_again_later);
    }

    private @NonNull String errorToMessage(@NonNull JoinGroupError error) {
        switch (error) {
            case GROUP_LINK_NOT_ACTIVE: return getString(R.string.GroupJoinBottomSheetDialogFragment_this_group_link_is_not_active);
            case NETWORK_ERROR        : return getString(R.string.GroupJoinBottomSheetDialogFragment_encountered_a_network_error);
            default                   : return getString(R.string.GroupJoinBottomSheetDialogFragment_unable_to_join_group_please_try_again_later);
        }
    }

    private GroupInviteLinkUrl getGroupInviteLinkUrl() {
        try {
            //noinspection ConstantConditions
            return GroupInviteLinkUrl.fromUri(requireArguments().getString(ARG_GROUP_INVITE_LINK_URL));
        } catch (GroupInviteLinkUrl.InvalidGroupLinkException | GroupInviteLinkUrl.UnknownGroupLinkVersionException e) {
            throw new AssertionError();
        }
    }

    @Override
    public void show(@NonNull FragmentManager manager, @Nullable String tag) {
        BottomSheetUtil.show(manager, tag, this);
    }

    private static final class FallbackPhotoProvider extends Recipient.FallbackPhotoProvider {
        @Override
        public @NonNull FallbackContactPhoto getPhotoForGroup() {
            return new ResourceContactPhoto(R.drawable.ic_group_outline_48);
        }
    }

    public enum ExtendedGroupJoinStatus {
        /**
         * No version of the client that can join V2 groups by link is in production.
         */
        COMING_SOON,

        /**
         * A newer version of the client is in production that will allow joining via GV2 group links.
         */
        UPDATE_TO_JOIN,

        /**
         * Locally we're using a version that can use group links, but one or more linked devices needs updating for GV2.
         */
        UPDATE_LINKED_DEVICE_TO_JOIN,

        /**
         * This version of the client allows joining via GV2 group links.
         */
        LOCAL_CAN_JOIN
    }
}