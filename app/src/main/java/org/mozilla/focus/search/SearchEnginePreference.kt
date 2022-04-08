/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.search

import android.app.AlertDialog
import android.content.Context
import org.mozilla.focus.search.SearchEngineManager
import org.mozilla.focus.search.SearchEngineAdapter
import org.mozilla.focus.R
import android.content.DialogInterface
import android.preference.DialogPreference
import android.util.AttributeSet
import org.mozilla.focus.utils.Settings

/**
 * Preference for setting the default search engine.
 */
class SearchEnginePreference : DialogPreference {
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {}
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
    }

    override fun onAttachedToActivity() {
        summary = SearchEngineManager.getInstance().getDefaultSearchEngine(context).getName()
        super.onAttachedToActivity()
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val adapter = SearchEngineAdapter(context)
        builder.setTitle(R.string.preference_dialog_title_search_engine)
        builder.setAdapter(adapter) { dialog, which ->
            persistSearchEngine(adapter.getItem(which))
            dialog.dismiss()
        }
        builder.setPositiveButton(null, null)
        builder.setNegativeButton(null, this)
    }

    private fun persistSearchEngine(searchEngine: SearchEngine) {
        summary = searchEngine.getName()
        Settings.getInstance(context)
            .setDefaultSearchEngine(searchEngine)
    }
}
