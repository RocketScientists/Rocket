/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.preference

import android.content.Context
import android.util.AttributeSet
import android.webkit.CookieManager
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.preference.MultiSelectListPreference
import org.mozilla.fileutils.FileUtils
import org.mozilla.fileutils.FileUtils.DeleteFolderRunnable
import org.mozilla.focus.R
import org.mozilla.focus.history.BrowsingHistoryManager
import org.mozilla.focus.telemetry.TelemetryWrapper.settingsEvent
import org.mozilla.rocket.component.PrivateSessionNotificationService.Companion.buildIntent
import org.mozilla.rocket.privately.PrivateMode.Companion.getInstance
import org.mozilla.threadutils.ThreadUtils

/**
 * Created by ylai on 2017/8/3.
 */
class CleanBrowsingDataPreference @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet?,
    defStyleAttr: Int = 0
) : MultiSelectListPreference(context, attrs, defStyleAttr) {

    override fun onAttached() {
        super.onAttached()
        dialogTitle = null

        val resources = context.resources

        // Workaround: always clear user's choice to ensure OnPreferenceChangeListener is invoked
        // if user choose any option. We need this workaround since there is no `onDialogClosed`
        // in androidx.preference.ListPreference
        persistStringSet(emptySet<String>())
        setDefaultValue(emptyArray<String>())
        isPersistent = false

        //  On click positive callback here get current value by getValues();
        val clearBrowsingHistory = resources.getString(R.string.pref_value_clear_browsing_history)
        val clearCookie = resources.getString(R.string.pref_value_clear_cookies)
        val clearCache = resources.getString(R.string.pref_value_clear_cache)
        val clearFormHistory = resources.getString(R.string.pref_value_clear_form_history)
        setOnPreferenceChangeListener { _, newValue ->
            val newValueMap: Set<String> = newValue as? Set<String>
                ?: return@setOnPreferenceChangeListener false
            for (value in newValueMap) {
                when (value) {
                    clearBrowsingHistory -> clearBrowsingHistory()
                    clearCookie -> clearCookie()
                    clearCache -> FileUtils.clearCache(context)
                    clearFormHistory -> WebViewDatabase.getInstance(context).clearFormData()
                }

                settingsEvent(key, value)
            }
            if (newValueMap.isNotEmpty()) {
                Toast.makeText(context, R.string.message_cleared_browsing_data, Toast.LENGTH_SHORT)
                    .show()
            }
            false
        }
    }

    private fun clearBrowsingHistory() {
        val file = FileUtils.getFaviconFolder(context)
        val runnable: Runnable = DeleteFolderRunnable(file)
        ThreadUtils.postToBackgroundThread(runnable)
        BrowsingHistoryManager.getInstance().deleteAll(null)
    }

    private fun clearCookie() {
        CookieManager.getInstance().removeAllCookies(null)
        // Also clear cookies in private mode process if the process exist
        if (getInstance(context).hasPrivateSession()) {
            // If there's a private mode process running, below intent will reach
            // PrivateModeActivity's onNewIntent, thus the activity won't appear again.
            // (assume that onNewIntent will always runs before onStart()
            // Fixme: we should rely on another Android component for IPC to clear CookieManager
            // Fixme: rather than rely on PrivateModeActivity cause it will cause UI issue easily
            val intent = buildIntent(context.applicationContext, true)
            context.startActivity(intent)
        }
    }
}
