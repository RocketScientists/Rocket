package org.mozilla.rocket.permission

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import org.mozilla.focus.R
import java.lang.ref.WeakReference

internal typealias Action = () -> Unit

/**
 * To help on validation whether a necessary permission is granted or not, before really perform
 * the given action.
 *
 * A helper class for applying ActivityResultLauncher, hence this help should be create as the
 * launcher.
 */
class PermissionHelper private constructor(
    private val hostFragment: Fragment,
    private val permission: String,
    @StringRes private val rationaleMsg: Int,
) {

    private val permissionLauncher: ActivityResultLauncher<String>

    private var grantedActionRef: WeakReference<Action>? = null
    private var rejectedActionRef: WeakReference<Action>? = null

    init {
        val contract = ActivityResultContracts.RequestPermission()
        permissionLauncher = hostFragment.registerForActivityResult(contract) { isGranted ->
            if (isGranted) {
                onPermissionGranted()
            } else {
                onPermissionDenied()
            }
        }
    }

    fun verifyPermissionAndRun(
        directAction: Action,
        rejectedAction: Action,
        grantedAction: Action = directAction
    ) {
        val context = hostFragment.context ?: return
        val checkResult = ContextCompat.checkSelfPermission(context, permission)
        if (checkResult == PackageManager.PERMISSION_GRANTED) {
            directAction()
            return
        }
        val shouldShowRationaleUi =
            hostFragment.shouldShowRequestPermissionRationale(permission)
        if (shouldShowRationaleUi) {
            showRationaleUi(hostFragment, rationaleMsg)
            return
        }

        grantedActionRef = WeakReference(grantedAction)
        rejectedActionRef = WeakReference(rejectedAction)
        permissionLauncher.launch(permission)
    }

    private fun onPermissionGranted() {
        val action = grantedActionRef?.get() ?: return
        action()
    }

    private fun onPermissionDenied() {
        val action = rejectedActionRef?.get() ?: return
        action()
    }

    private fun showRationaleUi(
        hostFragment: Fragment,
        @StringRes msgRes: Int,
        @StringRes buttonRes: Int = R.string.permission_handler_permission_dialog_setting
    ) {
        // TODO: should we implement PermissionHandler.isFirstTimeAsking ?
        val activity = hostFragment.activity ?: return
        val rootView = hostFragment.requireView()

        val snackBar = Snackbar.make(rootView, msgRes, Snackbar.LENGTH_LONG)

        snackBar.setAction(buttonRes) {
            val uri = Uri.fromParts("package", activity.packageName, null)
            val intent = Intent()
            intent.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = uri
            activity.startActivity(intent)
        }
        snackBar.show()
    }

    companion object {

        fun createHelperOnCreateStage(
            hostFragment: Fragment,
            permission: String,
            @StringRes rationaleMsg: Int
        ) = PermissionHelper(hostFragment, permission, rationaleMsg)
    }
}
