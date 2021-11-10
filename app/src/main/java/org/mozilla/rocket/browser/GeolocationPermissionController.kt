package org.mozilla.rocket.browser

import android.Manifest
import android.content.Context
import android.view.LayoutInflater
import android.webkit.GeolocationPermissions
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.mozilla.focus.R
import org.mozilla.focus.web.GeoPermissionCache
import org.mozilla.rocket.permission.Action
import org.mozilla.rocket.permission.PermissionHelper
import org.mozilla.threadutils.ThreadUtils

class GeolocationPermissionController(
    private val hostFragment: Fragment
) : DefaultLifecycleObserver {
    private var geolocationOrigin: String? = null
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geoDialog: AlertDialog? = null

    private lateinit var helper: PermissionHelper

    // onCreateFragment
    override fun onCreate(owner: LifecycleOwner) {
        helper = PermissionHelper.createHelperOnCreateStage(
            hostFragment,
            Manifest.permission.ACCESS_FINE_LOCATION,
            R.string.permission_toast_location
        )
    }

    fun showGeolocationDialog(origin: String, callback: GeolocationPermissions.Callback?) {
        val context = hostFragment.context ?: return
        geolocationOrigin = origin
        geolocationCallback = callback

        val directAction: Action = {
            showDialog(context)
        }

        val rejectAction: Action = {
            dismissGeolocationDialog()
            showMessageForPermissionDenied()
            rejectGeoRequest(false)
        }

        helper.verifyPermissionAndRun(
            directAction = directAction,
            rejectedAction = rejectAction
        )
    }

    fun dismissGeolocationDialog() {
        geolocationOrigin = null
        geolocationCallback = null
        dismissDialog()
    }

    private fun showMessageForPermissionDenied() {
        val context = hostFragment.context ?: return
        Toast.makeText(context, R.string.permission_toast_location_deny, Toast.LENGTH_LONG).show()
    }

    /**
     * show webView geolocation permission prompt
     */
    private fun showDialog(context: Context) {
        geolocationCallback ?: return
        if (geoDialog?.isShowing == true) {
            return
        }
        val allowed = GeoPermissionCache.getAllowed(geolocationOrigin)
        if (allowed != null) {
            geolocationCallback?.invoke(geolocationOrigin, allowed, false)
        } else {
            geoDialog = buildGeoPromptDialog(context).also { it.show() }
        }
    }

    private fun dismissDialog() {
        geoDialog?.dismiss()
        geoDialog = null
    }

    private fun acceptGeoRequest(cacheIt: Boolean) {
        geolocationCallback ?: return
        if (cacheIt) {
            GeoPermissionCache.putAllowed(geolocationOrigin, java.lang.Boolean.TRUE)
        }
        geolocationCallback?.invoke(geolocationOrigin, true, false)
        geolocationOrigin = ""
        geolocationCallback = null
    }

    private fun rejectGeoRequest(cacheIt: Boolean) {
        geolocationCallback ?: return

        // I'm not sure why it's so. This method already on Main thread.
        // But if I don't do this, webview will keeps requesting for permission.
        // See https://github.com/mozilla-tw/Rocket/blob/765f6a1ddbc2b9058813e930f63c62a9797c5fa0/app/src/webkit/java/org/mozilla/focus/webkit/FocusWebChromeClient.java#L126
        ThreadUtils.postToMainThread {
            if (cacheIt) {
                GeoPermissionCache.putAllowed(geolocationOrigin, java.lang.Boolean.FALSE)
            }
            geolocationCallback?.invoke(geolocationOrigin, false, false)
            geolocationOrigin = ""
            geolocationCallback = null
        }
    }

    @VisibleForTesting
    fun buildGeoPromptDialog(context: Context): AlertDialog {
        val inflater = LayoutInflater.from(context)
        val customContent = inflater.inflate(R.layout.dialog_permission_request, null)
        val appName = context.getString(R.string.app_name)

        val checkBox = customContent.findViewById<CheckedTextView>(R.id.cache_my_decision)
        checkBox.text = context.getString(R.string.geolocation_dialog_message_cache_it, appName)
        checkBox.setOnClickListener { checkBox.toggle() }

        val builder = AlertDialog.Builder(context)
        builder.setView(customContent)
            .setMessage(context.getString(R.string.geolocation_dialog_message, geolocationOrigin))
            .setCancelable(true)
            .setPositiveButton(context.getString(R.string.geolocation_dialog_allow)) { _, _ ->
                acceptGeoRequest(checkBox.isChecked)
            }
            .setNegativeButton(context.getString(R.string.geolocation_dialog_block)) { _, _ ->
                rejectGeoRequest(checkBox.isChecked)
            }
            .setOnDismissListener { rejectGeoRequest(false) }
            .setOnCancelListener { rejectGeoRequest(false) }
        return builder.create()
    }
}
