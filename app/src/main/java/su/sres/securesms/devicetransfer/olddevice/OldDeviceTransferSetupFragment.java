package su.sres.securesms.devicetransfer.olddevice;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.navigation.fragment.NavHostFragment;

import su.sres.devicetransfer.DeviceToDeviceTransferService;
import su.sres.securesms.ApplicationPreferencesActivity;
import su.sres.securesms.R;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.devicetransfer.DeviceTransferSetupFragment;
import su.sres.securesms.devicetransfer.SetupStep;
import su.sres.securesms.jobs.LocalBackupJob;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.notifications.NotificationIds;

/**
 * Most responsibility is in {@link DeviceTransferSetupFragment} and delegates here
 * for strings and behavior relevant to setting up device transfer for the old device.
 *
 * Also responsible for setting up {@link DeviceToDeviceTransferService}.
 */
public final class OldDeviceTransferSetupFragment extends DeviceTransferSetupFragment {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ApplicationDependencies.getJobManager().cancelAllInQueue(LocalBackupJob.QUEUE);
    }

    @Override
    protected void navigateAwayFromTransfer() {
        NavHostFragment.findNavController(this).popBackStack();
    }

    @Override
    protected void navigateToTransferConnected() {
        NavHostFragment.findNavController(this).navigate(R.id.action_oldDeviceTransferSetup_to_oldDeviceTransfer);
    }

    @Override
    protected void navigateWhenWifiDirectUnavailable() {
        Intent intent = new Intent(requireContext(), ApplicationPreferencesActivity.class);
        intent.putExtra(ApplicationPreferencesActivity.LAUNCH_TO_BACKUPS_FRAGMENT, true);
        startActivity(intent);
        requireActivity().finish();
    }

    @Override
    protected void startTransfer() {
        Intent intent = new Intent(requireContext(), OldDeviceTransferActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(requireContext(), 0, intent, 0);

        DeviceToDeviceTransferService.TransferNotificationData notificationData = new DeviceToDeviceTransferService.TransferNotificationData(NotificationIds.DEVICE_TRANSFER, NotificationChannels.BACKUPS, R.drawable.ic_signal_backup);
        DeviceToDeviceTransferService.startClient(requireContext(), new OldDeviceClientTask(), notificationData, pendingIntent);
    }

    @Override
    protected @StringRes int getErrorTextForStep(@NonNull SetupStep step) {
        switch (step) {
            case PERMISSIONS_DENIED:
                return R.string.OldDeviceTransferSetup__signal_needs_the_location_permission_to_discover_and_connect_with_your_new_device;
            case LOCATION_DISABLED:
                return R.string.OldDeviceTransferSetup__signal_needs_location_services_enabled_to_discover_and_connect_with_your_new_device;
            case WIFI_DISABLED:
                return R.string.OldDeviceTransferSetup__signal_needs_wifi_on_to_discover_and_connect_with_your_new_device;
            case WIFI_DIRECT_UNAVAILABLE:
                return R.string.OldDeviceTransferSetup__sorry_it_appears_your_device_does_not_support_wifi_direct;
            case ERROR:
                return R.string.OldDeviceTransferSetup__an_unexpected_error_occurred_while_attempting_to_connect_to_your_old_device;
        }
        throw new AssertionError("No error text for step: " + step);
    }

    @Override
    protected @StringRes int getErrorResolveButtonTextForStep(@NonNull SetupStep step) {
        if (step == SetupStep.WIFI_DIRECT_UNAVAILABLE) {
            return R.string.OldDeviceTransferSetup__create_a_backup;
        }
        throw new AssertionError("No error resolve button text for step: " + step);
    }

    @Override
    protected @StringRes int getStatusTextForStep(@NonNull SetupStep step, boolean takingTooLongInStep) {
        switch (step) {
            case SETTING_UP:
            case WAITING:
                return R.string.OldDeviceTransferSetup__searching_for_new_android_device;
            case ERROR:
                return R.string.OldDeviceTransferSetup__an_unexpected_error_occurred_while_attempting_to_connect_to_your_old_device;
            case TROUBLESHOOTING:
                return R.string.DeviceTransferSetup__unable_to_discover_new_device;
        }
        throw new AssertionError("No status text for step: " + step);
    }
}
