package org.mozilla.rocket.download

import android.Manifest
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import org.mozilla.permissionhandler.PermissionHandler
import org.mozilla.rocket.browser.BrowserFragment
import org.mozilla.rocket.tabs.web.Download

class BrowserDownloadCallback(
    private val fragment: Fragment,
    private val permissionHandler: PermissionHandler
) : org.mozilla.rocket.tabs.web.DownloadCallback {
    override fun onDownloadStart(download: Download) {
        val activity = fragment.activity ?: return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return
        }
        permissionHandler.tryAction(
            fragment,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            BrowserFragment.ACTION_DOWNLOAD,
            download
        )
    }
}
