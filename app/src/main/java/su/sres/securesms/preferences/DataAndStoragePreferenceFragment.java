package su.sres.securesms.preferences;

import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import su.sres.core.util.logging.Log;
import su.sres.securesms.ApplicationPreferencesActivity;
import su.sres.securesms.R;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.service.WebRtcCallService;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.Util;
import su.sres.securesms.webrtc.CallBandwidthMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class DataAndStoragePreferenceFragment extends ListSummaryPreferenceFragment {

    private static final String TAG                = Log.tag(DataAndStoragePreferenceFragment.class);
    private static final String MANAGE_STORAGE_KEY = "pref_data_manage";
    private static final String USE_PROXY_KEY      = "pref_use_proxy";

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        findPreference(TextSecurePreferences.MEDIA_DOWNLOAD_MOBILE_PREF)
                .setOnPreferenceChangeListener(new MediaDownloadChangeListener());
        findPreference(TextSecurePreferences.MEDIA_DOWNLOAD_WIFI_PREF)
                .setOnPreferenceChangeListener(new MediaDownloadChangeListener());
        findPreference(TextSecurePreferences.MEDIA_DOWNLOAD_ROAMING_PREF)
                .setOnPreferenceChangeListener(new MediaDownloadChangeListener());

        findPreference(TextSecurePreferences.CALL_BANDWIDTH_PREF)
                .setOnPreferenceChangeListener(new CallBandwidthChangeListener());
        initializeListSummary((ListPreference) findPreference(TextSecurePreferences.CALL_BANDWIDTH_PREF));

        Preference manageStorage = findPreference(MANAGE_STORAGE_KEY);
        manageStorage.setOnPreferenceClickListener(unused -> {
            requireApplicationPreferencesActivity().pushFragment(new StoragePreferenceFragment());
            return false;
        });

        ApplicationPreferencesViewModel viewModel = ApplicationPreferencesViewModel.getApplicationPreferencesViewModel(requireActivity());

        viewModel.getStorageBreakdown()
                .observe(requireActivity(),
                        breakdown -> manageStorage.setSummary(Util.getPrettyFileSize(breakdown.getTotalSize())));

        findPreference(USE_PROXY_KEY).setOnPreferenceClickListener(unused -> {
            requireApplicationPreferencesActivity().pushFragment(EditProxyFragment.newInstance());
            return false;
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_data_and_storage);
    }

    @Override
    public void onResume() {
        super.onResume();
        requireApplicationPreferencesActivity().getSupportActionBar().setTitle(R.string.preferences__data_and_storage);
        setMediaDownloadSummaries();
        ApplicationPreferencesViewModel.getApplicationPreferencesViewModel(requireActivity()).refreshStorageBreakdown(requireContext());
        findPreference(USE_PROXY_KEY).setSummary(SignalStore.proxy().isProxyEnabled() ? R.string.preferences_on : R.string.preferences_off);
    }

    private @NonNull ApplicationPreferencesActivity requireApplicationPreferencesActivity() {
        return (ApplicationPreferencesActivity) requireActivity();
    }

    private void setMediaDownloadSummaries() {
        findPreference(TextSecurePreferences.MEDIA_DOWNLOAD_MOBILE_PREF)
                .setSummary(getSummaryForMediaPreference(TextSecurePreferences.getMobileMediaDownloadAllowed(getActivity())));
        findPreference(TextSecurePreferences.MEDIA_DOWNLOAD_WIFI_PREF)
                .setSummary(getSummaryForMediaPreference(TextSecurePreferences.getWifiMediaDownloadAllowed(getActivity())));
        findPreference(TextSecurePreferences.MEDIA_DOWNLOAD_ROAMING_PREF)
                .setSummary(getSummaryForMediaPreference(TextSecurePreferences.getRoamingMediaDownloadAllowed(getActivity())));
    }

    private CharSequence getSummaryForMediaPreference(Set<String> allowedNetworks) {
        String[]     keys      = getResources().getStringArray(R.array.pref_media_download_entries);
        String[]     values    = getResources().getStringArray(R.array.pref_media_download_values);
        List<String> outValues = new ArrayList<>(allowedNetworks.size());

        for (int i=0; i < keys.length; i++) {
            if (allowedNetworks.contains(keys[i])) outValues.add(values[i]);
        }

        return outValues.isEmpty() ? getResources().getString(R.string.preferences__none)
                : TextUtils.join(", ", outValues);
    }

    private class MediaDownloadChangeListener implements Preference.OnPreferenceChangeListener {
        @SuppressWarnings("unchecked")
        @Override public boolean onPreferenceChange(Preference preference, Object newValue) {
            Log.i(TAG, "onPreferenceChange");
            preference.setSummary(getSummaryForMediaPreference((Set<String>)newValue));
            return true;
        }
    }

    private class CallBandwidthChangeListener extends ListSummaryListener {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
            ListPreference listPref   = (ListPreference) preference;
            int            entryIndex = Arrays.asList(listPref.getEntryValues()).indexOf(value);

            switch (entryIndex) {
                case 0:
                    SignalStore.settings().setCallBandwidthMode(CallBandwidthMode.HIGH_ALWAYS);
                    break;
                case 1:
                    SignalStore.settings().setCallBandwidthMode(CallBandwidthMode.HIGH_ON_WIFI);
                    break;
                case 2:
                    SignalStore.settings().setCallBandwidthMode(CallBandwidthMode.LOW_ALWAYS);
                    break;
                default:
                    throw new AssertionError();
            }

            WebRtcCallService.notifyBandwidthModeUpdated(requireContext());

            return super.onPreferenceChange(preference, value);
        }
    }
}
