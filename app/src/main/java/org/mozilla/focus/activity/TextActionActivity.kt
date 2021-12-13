/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.activity

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.SafeIntent
import org.mozilla.focus.utils.SearchUtils
import org.mozilla.rocket.component.LaunchIntentDispatcher.LaunchMethod

/**
 * Activity for receiving and processing an ACTION_PROCESS_TEXT intent.
 */
class TextActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = SafeIntent(intent)

        val searchIntent = Intent()
        searchIntent.setClassName(this, AppConstants.LAUNCHER_ACTIVITY_ALIAS)
        searchIntent.action = Intent.ACTION_VIEW

        val searchText = getSearchText(intent)
        val searchUrl = SearchUtils.createSearchUrl(this, searchText)
        searchIntent.data = Uri.parse(searchUrl)

        val extraKey: String? = getExtraKey(intent)
        if (!extraKey.isNullOrEmpty()) {
            searchIntent.putExtra(extraKey, true)
        }

        startActivity(searchIntent)
        finish()
    }

    private fun getSearchText(intent: SafeIntent): String {
        val nullableSearchText = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getProcessTextIfSdkSatisfied()
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
            else -> null
        }

        return nullableSearchText ?: ""
    }

    private fun getExtraKey(intent: SafeIntent): String? = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT -> LaunchMethod.EXTRA_BOOL_TEXT_SELECTION.value
        Intent.ACTION_WEB_SEARCH -> LaunchMethod.EXTRA_BOOL_WEB_SEARCH.value
        else -> null
    }

    private fun SafeIntent.getProcessTextIfSdkSatisfied(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        } else {
            null
        }
    }
}
