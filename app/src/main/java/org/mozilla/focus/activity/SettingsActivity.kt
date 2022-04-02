/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.activity

import android.content.Context
import org.mozilla.focus.settings.SettingsFragment.Companion.newInstance
import org.mozilla.focus.activity.BaseActivity
import android.os.Bundle
import org.mozilla.focus.R
import android.content.Intent
import android.view.View
import androidx.appcompat.widget.Toolbar
import org.mozilla.focus.activity.SettingsActivity
import org.mozilla.focus.settings.SettingsFragment

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar!!
        actionBar.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        val intent = intent
        val action =
            if (intent != null && intent.getStringExtra(EXTRA_ACTION) != null) intent.getStringExtra(
                EXTRA_ACTION
            ) else ""
        fragmentManager.beginTransaction()
            .replace(R.id.container, newInstance(action!!), SettingsFragment.TAG)
            .commit()

        // Ensure all locale specific Strings are initialised on first run, we don't set the title
        // anywhere before now (the title can only be set via AndroidManifest, and ensuring
        // that that loads the correct locale string is tricky).
        applyLocale()
    }

    // Need to pass the new intent which may trigger from the deep-link to the SettingsFragment
    // or it won't be acted as expected when the settings page is already in foreground.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fragment = fragmentManager.findFragmentByTag(SettingsFragment.TAG)
        if (fragment is SettingsFragment) {
            fragment.onNewIntent(intent)
        }
    }

    override fun applyLocale() {
        setTitle(R.string.menu_settings)
    }

    companion object {
        const val ACTIVITY_RESULT_LOCALE_CHANGED = 1
        const val EXTRA_ACTION = "action"
        fun getStartIntent(context: Context?, action: String?): Intent {
            return Intent(context, SettingsActivity::class.java).putExtra(EXTRA_ACTION, action)
        }
    }
}
