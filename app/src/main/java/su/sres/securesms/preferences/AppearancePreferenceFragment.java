package su.sres.securesms.preferences;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;

import su.sres.securesms.ApplicationPreferencesActivity;
import su.sres.securesms.R;
import su.sres.securesms.util.ActivityTransitionUtil;
import su.sres.securesms.util.DynamicTheme;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.wallpaper.ChatWallpaperActivity;

import java.util.Arrays;

public class AppearancePreferenceFragment extends ListSummaryPreferenceFragment {

  private static final String WALLPAPER_PREF = "pref_wallpaper";

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    this.findPreference(TextSecurePreferences.THEME_PREF).setOnPreferenceChangeListener(new ListSummaryListener());
    this.findPreference(TextSecurePreferences.LANGUAGE_PREF).setOnPreferenceChangeListener(new ListSummaryListener());
    this.findPreference(WALLPAPER_PREF).setOnPreferenceClickListener(preference -> {
      startActivity(ChatWallpaperActivity.createIntent(requireContext()));
      ActivityTransitionUtil.setSlideInTransition(requireActivity());
      return true;
    });

    initializeListSummary((ListPreference)findPreference(TextSecurePreferences.THEME_PREF));
    initializeListSummary((ListPreference)findPreference(TextSecurePreferences.LANGUAGE_PREF));
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_appearance);
  }

  @Override
  public void onStart() {
    super.onStart();
    getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener((ApplicationPreferencesActivity)getActivity());
  }

  @Override
  public void onResume() {
    super.onResume();
    ((ApplicationPreferencesActivity) getActivity()).getSupportActionBar().setTitle(R.string.preferences__appearance);
  }

  @Override
  public void onStop() {
    super.onStop();
    getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener((ApplicationPreferencesActivity) getActivity());
  }

  public static CharSequence getSummary(Context context) {
    String[] languageEntries     = context.getResources().getStringArray(R.array.language_entries);
    String[] languageEntryValues = context.getResources().getStringArray(R.array.language_values);
    String[] themeEntries        = context.getResources().getStringArray(R.array.pref_theme_entries);
    String[] themeEntryValues    = context.getResources().getStringArray(R.array.pref_theme_values);

    int langIndex  = Arrays.asList(languageEntryValues).indexOf(TextSecurePreferences.getLanguage(context));
    int themeIndex = Arrays.asList(themeEntryValues).indexOf(TextSecurePreferences.getTheme(context));

    if (langIndex == -1)  langIndex = 0;
    if (themeIndex == -1) themeIndex = 0;

    return context.getString(R.string.ApplicationPreferencesActivity_appearance_summary,
                             themeEntries[themeIndex],
                             languageEntries[langIndex]);
  }
}
