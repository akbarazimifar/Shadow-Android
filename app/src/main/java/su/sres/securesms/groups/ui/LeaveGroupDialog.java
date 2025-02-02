package su.sres.securesms.groups.ui;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.annimon.stream.Stream;

import java.io.IOException;
import java.util.List;

import su.sres.securesms.R;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.groups.GroupChangeException;
import su.sres.securesms.groups.GroupId;
import su.sres.securesms.groups.GroupManager;
import su.sres.securesms.groups.ui.chooseadmin.ChooseNewAdminActivity;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.concurrent.SimpleTask;
import su.sres.securesms.util.views.SimpleProgressDialog;

public final class LeaveGroupDialog {

    private static final String TAG = Log.tag(LeaveGroupDialog.class);

    @NonNull  private final FragmentActivity activity;
    @NonNull  private final GroupId.Push     groupId;
    @Nullable private final Runnable         onSuccess;

    public static void handleLeavePushGroup(@NonNull FragmentActivity activity,
                                            @NonNull GroupId.Push groupId,
                                            @Nullable Runnable onSuccess) {
        new LeaveGroupDialog(activity, groupId, onSuccess).show();
    }

    private LeaveGroupDialog(@NonNull FragmentActivity activity,
                             @NonNull GroupId.Push groupId,
                             @Nullable Runnable onSuccess) {
        this.activity  = activity;
        this.groupId   = groupId;
        this.onSuccess = onSuccess;
    }

    public void show() {
        if (!groupId.isV2()) {
            showLeaveDialog();
            return;
        }

        SimpleTask.run(activity.getLifecycle(), () -> {
            GroupDatabase.V2GroupProperties groupProperties = DatabaseFactory.getGroupDatabase(activity)
                    .getGroup(groupId)
                    .transform(GroupDatabase.GroupRecord::requireV2GroupProperties)
                    .orNull();

            if (groupProperties != null && groupProperties.isAdmin(Recipient.self())) {
                List<Recipient> otherMemberRecipients = groupProperties.getMemberRecipients(GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);
                long            otherAdminsCount      = Stream.of(otherMemberRecipients).filter(groupProperties::isAdmin).count();

                return otherAdminsCount == 0 && !otherMemberRecipients.isEmpty();
            }

            return false;
        }, mustSelectNewAdmin -> {
            if (mustSelectNewAdmin) {
                showSelectNewAdminDialog();
            } else {
                showLeaveDialog();
            }
        });
    }

    private void showSelectNewAdminDialog() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.LeaveGroupDialog_choose_new_admin)
                .setMessage(R.string.LeaveGroupDialog_before_you_leave_you_must_choose_at_least_one_new_admin_for_this_group)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.LeaveGroupDialog_choose_admin, (d,w) -> activity.startActivity(ChooseNewAdminActivity.createIntent(activity, groupId.requireV2())))
                .show();
    }

    private void showLeaveDialog() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.LeaveGroupDialog_leave_group)
                .setCancelable(true)
                .setMessage(R.string.LeaveGroupDialog_you_will_no_longer_be_able_to_send_or_receive_messages_in_this_group)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.LeaveGroupDialog_leave, (dialog, which) -> {
                    AlertDialog progressDialog = SimpleProgressDialog.show(activity);
                    SimpleTask.run(activity.getLifecycle(), this::leaveGroup, result -> {
                        progressDialog.dismiss();
                        handleLeaveGroupResult(result);
                    });
                })
                .show();
    }

    private @NonNull GroupChangeResult leaveGroup() {
        try {
            GroupManager.leaveGroup(activity, groupId);
            return GroupChangeResult.SUCCESS;
        } catch (GroupChangeException | IOException e) {
            Log.w(TAG, e);
            return GroupChangeResult.failure(GroupChangeFailureReason.fromException(e));
        }
    }

    private void handleLeaveGroupResult(@NonNull GroupChangeResult result) {
        if (result.isSuccess()) {
            if (onSuccess != null) onSuccess.run();
        } else {
            Toast.makeText(activity, GroupErrors.getUserDisplayMessage(result.getFailureReason()), Toast.LENGTH_LONG).show();
        }
    }
}