package su.sres.securesms.devicetransfer.olddevice;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import su.sres.core.util.logging.Log;
import su.sres.securesms.R;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.net.DeviceTransferBlockingInterceptor;

/**
 * Blocking dialog shown on old devices after a successful transfer to prevent use unless
 * the user takes action to reactivate.
 */
public final class OldDeviceTransferLockedDialog extends DialogFragment {

    private static final String TAG          = Log.tag(OldDeviceTransferLockedDialog.class);
    private static final String FRAGMENT_TAG = "OldDeviceTransferLockedDialog";

    public static void show(@NonNull FragmentManager fragmentManager) {
        if (fragmentManager.findFragmentByTag(FRAGMENT_TAG) != null) {
            Log.i(TAG, "Locked dialog already being shown");
            return;
        }

        new OldDeviceTransferLockedDialog().show(fragmentManager, FRAGMENT_TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(requireContext(), R.style.Signal_ThemeOverlay_Dialog_Rounded);
        dialogBuilder.setView(R.layout.old_device_transfer_locked_dialog_fragment)
                .setPositiveButton(R.string.OldDeviceTransferLockedDialog__done, (d, w) -> OldDeviceExitActivity.exit(requireActivity()))
                .setNegativeButton(R.string.OldDeviceTransferLockedDialog__cancel_and_activate_this_device, (d, w) -> onUnlockRequest());

        Dialog dialog = dialogBuilder.create();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        return dialog;
    }

    private void onUnlockRequest() {
        SignalStore.misc().clearOldDeviceTransferLocked();
        DeviceTransferBlockingInterceptor.getInstance().unblockNetwork();
    }
}
