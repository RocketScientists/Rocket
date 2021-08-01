package org.mozilla.rocket.browser

import android.Manifest
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import org.mozilla.focus.R
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.rocket.permission.Action
import org.mozilla.rocket.permission.PermissionHelper
import org.mozilla.rocket.tabs.web.Download

class DownloadController(private val hostFragment: BrowserFragment) : LifecycleObserver {

    private lateinit var helper: PermissionHelper

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreateFragment() {
        helper = PermissionHelper.createHelperOnCreateStage(
            hostFragment,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            R.string.permission_toast_storage
        )
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroyFragment() {
        clearReferences()
    }

    fun maybeQueueDownload(params: Parcelable?) {
        val activity = hostFragment.activity
        if (activity == null) {
            val msg = "No context to use, abort callback onDownloadStart"
            Log.w(ScreenNavigator.BROWSER_FRAGMENT_TAG, msg)
            return
        }

        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return
        }

        val download = params as? Download ?: return

        val directAction: Action = {
            hostFragment.addToDownloadManager(download)
        }

        val grantedAction: Action = {
            hostFragment.addToDownloadManager(download)
        }

        val rejectAction: Action = {
            clearReferences()
            showMessageForPermissionDenied()
        }

        helper.verifyPermissionAndRun(
            directAction = directAction,
            grantedAction = grantedAction,
            rejectedAction = rejectAction
        )
    }

    private fun clearReferences() {
        // nothing need to be cleared yet
    }

    private fun showMessageForPermissionDenied() {
        val context = hostFragment.context ?: return
        Toast.makeText(context, R.string.permission_toast_storage_deny, Toast.LENGTH_LONG).show()
    }
}
