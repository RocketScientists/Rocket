/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import org.mozilla.focus.R;
import org.mozilla.focus.settings.SettingsFragment;

public class SettingsActivity extends BaseActivity {
    public static final int ACTIVITY_RESULT_LOCALE_CHANGED = 1;
    public static final String EXTRA_ACTION = "action";

    public static Intent getStartIntent(final Context context, final String action) {
        return new Intent(context, SettingsActivity.class).putExtra(EXTRA_ACTION, action);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        final ActionBar actionBar = getSupportActionBar();
        assert actionBar != null;

        actionBar.setDisplayHomeAsUpEnabled(true);

        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        final Intent intent = getIntent();
        String action = (intent != null && intent.getStringExtra(EXTRA_ACTION) != null) ?
                intent.getStringExtra(EXTRA_ACTION) : "";

        getFragmentManager().beginTransaction()
                .replace(R.id.container, SettingsFragment.newInstance(action), SettingsFragment.TAG)
                .commit();

        // Ensure all locale specific Strings are initialised on first run, we don't set the title
        // anywhere before now (the title can only be set via AndroidManifest, and ensuring
        // that that loads the correct locale string is tricky).
        applyLocale();
    }

    // Need to pass the new intent which may trigger from the deep-link to the SettingsFragment
    // or it won't be acted as expected when the settings page is already in foreground.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        android.app.Fragment fragment = getFragmentManager().findFragmentByTag(SettingsFragment.TAG);
        if (fragment instanceof SettingsFragment) {
            ((SettingsFragment) fragment).onNewIntent(intent);
        }
    }

    @Override
    public void applyLocale() {
        setTitle(R.string.menu_settings);
    }
}
