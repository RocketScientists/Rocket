/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import org.mozilla.focus.R
import org.mozilla.focus.search.SearchEngine
import org.mozilla.focus.search.SearchEngineManager

/**
 * Preference for setting the default search engine.
 */
class SearchEnginePreference : ListPreference {
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    override fun onAttached() {
        super.onAttached()
        summary = SearchEngineManager.getInstance().getDefaultSearchEngine(context).name
        setTitle(R.string.preference_dialog_title_search_engine)
        buildList()
    }

    private fun buildList() {
        val searchEngines: List<SearchEngine> = SearchEngineManager.getInstance().searchEngines
        val names = searchEngines.map { it.name }

        entries = names.toTypedArray()
        // TODO: consider to use SearchEngine.identifier that makes more sense
        entryValues = names.toTypedArray()

        setOnPreferenceChangeListener { _, newValue ->
            val chosenSearchEngine = searchEngines
                .findLast { it.name == newValue }
            if (chosenSearchEngine != null) {
                persistSearchEngine(chosenSearchEngine)
            }
            true
        }
    }

    private fun persistSearchEngine(searchEngine: SearchEngine) {
        summary = searchEngine.name
        SearchEngineManager.getInstance().setDefaultSearchEngine(context, searchEngine)
    }
}
