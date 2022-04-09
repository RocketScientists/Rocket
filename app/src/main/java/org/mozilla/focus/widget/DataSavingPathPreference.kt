/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.widget

import android.content.Context
import android.preference.ListPreference
import android.text.TextUtils
import android.util.AttributeSet
import androidx.annotation.WorkerThread
import org.mozilla.focus.R
import org.mozilla.focus.utils.NoRemovableStorageException
import org.mozilla.focus.utils.StorageUtils
import org.mozilla.threadutils.ThreadUtils

class DataSavingPathPreference @JvmOverloads constructor(
    context: Context?,
    attributes: AttributeSet? = null
) : ListPreference(context, attributes) {

    private var hasRemovableStorage = false

    override fun onAttachedToActivity() {
        super.onAttachedToActivity()
        buildList()
        // Put pingRemovableStorage() in background thread to avoid strict mode violation: disk I/O on main thread.
        ThreadUtils.postToBackgroundThread { pingRemovableStorage() }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        // The superclass will take care of persistence.
        super.onDialogClosed(positiveResult)
        if (positiveResult) {
            persistString(value)
        }
    }

    override fun getSummary(): CharSequence {
        // design's spec, always show 'save to internal' if there is no removable storage
        if (!hasRemovableStorage) {
            return context.resources.getString(R.string.setting_dialog_internal_storage)
        }
        if (TextUtils.isEmpty(entry)) {
            val entries = context.resources.getStringArray(R.array.data_saving_path_entries)
            setValueIndex(0)
            return entries[0]
        }
        return entry
    }

    private fun buildList() {
        val entries = context.resources.getStringArray(R.array.data_saving_path_entries)
        val values = context.resources.getStringArray(R.array.data_saving_path_values)
        setEntries(entries)
        entryValues = values
    }

    @WorkerThread
    private fun pingRemovableStorage() {
        hasRemovableStorage = try {
            // This must be called in a background thread cause it has I/O access.
            StorageUtils.getAppMediaDirOnRemovableStorage(context)
            // no exception
            true
        } catch (e: NoRemovableStorageException) {
            false
        }
        super.setEnabled(hasRemovableStorage)

        // notifyChanged() will update the UI so it must be called in main thread.
        ThreadUtils.postToMainThread { // ensure Summary sync to current state
            notifyChanged()
        }
    }
}
