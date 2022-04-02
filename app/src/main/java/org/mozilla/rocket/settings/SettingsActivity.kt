/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.mozilla.focus.R
import org.mozilla.focus.activity.BaseActivity
import org.mozilla.focus.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()

        // Ensure all locale specific Strings are initialised on first run, we don't set the title
        // anywhere before now (the title can only be set via AndroidManifest, and ensuring
        // that that loads the correct locale string is tricky).
        applyLocale()
    }

    // Need to pass the new intent which may trigger from the deep-link to the SettingsFragment
    // or it won't be acted as expected when the settings page is already in foreground.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fragment = supportFragmentManager.findFragmentByTag(SettingsFragment.TAG)
        if (fragment is SettingsFragment) {
            fragment.onNewIntent(intent)
        }
    }

    override fun applyLocale() {
        setTitle(R.string.menu_settings)
    }

    private fun initView() {
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
            .also { setContentView(it.root) }
        setContentView(R.layout.activity_settings)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        showFragment()
    }

    private fun showFragment() {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ""
        val settingsFragment = SettingsFragment.newInstance(action)
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, settingsFragment, SettingsFragment.TAG)
            .commit()
    }

    companion object {

        const val ACTIVITY_RESULT_LOCALE_CHANGED = 1
        const val EXTRA_ACTION = "action"

        fun getStartIntent(context: Context?, action: String?): Intent {
            return Intent(context, SettingsActivity::class.java).putExtra(EXTRA_ACTION, action)
        }
    }
}
