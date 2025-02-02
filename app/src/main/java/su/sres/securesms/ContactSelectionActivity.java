/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.securesms;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import su.sres.core.util.logging.Log;

import su.sres.securesms.components.ContactFilterToolbar;
import su.sres.securesms.contacts.ContactsCursorLoader.DisplayMode;
import su.sres.securesms.contacts.sync.DirectoryHelper;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.DynamicNoActionBarTheme;
import su.sres.securesms.util.DynamicTheme;
import su.sres.securesms.util.ServiceUtil;
import su.sres.securesms.util.TextSecurePreferences;

import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Base activity container for selecting a list of contacts.
 *
 * @author Moxie Marlinspike
 *
 */
public abstract class ContactSelectionActivity extends PassphraseRequiredActivity
                                               implements SwipeRefreshLayout.OnRefreshListener,
        ContactSelectionListFragment.OnContactSelectedListener,
        ContactSelectionListFragment.ScrollCallback
{
  private static final String TAG = ContactSelectionActivity.class.getSimpleName();

  public static final String EXTRA_LAYOUT_RES_ID = "layout_res_id";

  private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

  protected ContactSelectionListFragment contactsFragment;

  private ContactFilterToolbar toolbar;

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle icicle, boolean ready) {
    if (!getIntent().hasExtra(ContactSelectionListFragment.DISPLAY_MODE)) {
      int displayMode = TextSecurePreferences.isSmsEnabled(this) ? DisplayMode.FLAG_ALL
              : DisplayMode.FLAG_PUSH | DisplayMode.FLAG_ACTIVE_GROUPS | DisplayMode.FLAG_INACTIVE_GROUPS | DisplayMode.FLAG_SELF;
      getIntent().putExtra(ContactSelectionListFragment.DISPLAY_MODE, displayMode);
    }

    setContentView(getIntent().getIntExtra(EXTRA_LAYOUT_RES_ID, R.layout.contact_selection_activity));

    initializeToolbar();
    initializeResources();
    initializeSearch();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  protected ContactFilterToolbar getToolbar() {
    return toolbar;
  }

  private void initializeToolbar() {
    this.toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    getSupportActionBar().setDisplayHomeAsUpEnabled(false);
    getSupportActionBar().setDisplayShowTitleEnabled(false);
    getSupportActionBar().setIcon(null);
    getSupportActionBar().setLogo(null);
  }

  private void initializeResources() {
    contactsFragment = (ContactSelectionListFragment) getSupportFragmentManager().findFragmentById(R.id.contact_selection_list_fragment);
    contactsFragment.setOnRefreshListener(this);
  }

  private void initializeSearch() {
    toolbar.setOnFilterChangedListener(filter -> contactsFragment.setQueryFilter(filter));
  }

  @Override
  public void onRefresh() {
    new RefreshDirectoryTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, getApplicationContext());
  }

  @Override
  public boolean onBeforeContactSelected(Optional<RecipientId> recipientId, String number) {
    return true;
  }

  @Override
  public void onContactDeselected(Optional<RecipientId> recipientId, String number) {}

  @Override
  public void onBeginScroll() {
    hideKeyboard();
  }

  private void hideKeyboard() {
    ServiceUtil.getInputMethodManager(this)
            .hideSoftInputFromWindow(toolbar.getWindowToken(), 0);
    toolbar.clearFocus();
  }

  private static class RefreshDirectoryTask extends AsyncTask<Context, Void, Void> {

    private final WeakReference<ContactSelectionActivity> activity;

    private RefreshDirectoryTask(ContactSelectionActivity activity) {
      this.activity = new WeakReference<>(activity);
    }

    @Override
    protected Void doInBackground(Context... params) {

      try {
        DirectoryHelper.refreshDirectory(params[0]);
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      return null;
    }

    @Override
    protected void onPostExecute(Void result) {
      ContactSelectionActivity activity = this.activity.get();

      if (activity != null && !activity.isFinishing()) {
        activity.toolbar.clear();
        activity.contactsFragment.resetQueryFilter();
      }
    }
  }
}
