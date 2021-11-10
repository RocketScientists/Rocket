package org.mozilla.rocket.browser

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.CallSuper
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.mozilla.focus.R
import org.mozilla.focus.utils.FilePickerUtil
import org.mozilla.rocket.permission.Action
import org.mozilla.rocket.permission.PermissionHelper

class FileChooseController(private val hostFragment: Fragment) : DefaultLifecycleObserver {

    private lateinit var helper: PermissionHelper
    private lateinit var chooserLauncher: ActivityResultLauncher<FileChooserParams>
    private var callback: ValueCallback<Array<Uri>>? = null
    private var params: FileChooserParams? = null

    // onCreateFragment
    override fun onCreate(owner: LifecycleOwner) {
        helper = PermissionHelper.createHelperOnCreateStage(
            hostFragment,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            R.string.permission_toast_storage
        )

        val contract = FilePickerContract()
        chooserLauncher = hostFragment.registerForActivityResult(contract) {
            try {
                val resultUris = if (it == null) null else arrayOf(it)
                // if file locates on external storage and we haven't granted permission
                // we might get exception here. but try won't work here.
                callback?.onReceiveValue(resultUris)
            } catch (e: Exception) {
                callback?.onReceiveValue(null)
                e.printStackTrace()
            }
        }
    }

    // onDestroyFragment
    override fun onDestroy(owner: LifecycleOwner) {
        clearReferences()
    }

    fun maybeChooseFile(
        callback: ValueCallback<Array<Uri>>?,
        params: FileChooserParams
    ) {
        this.callback = callback
        this.params = params

        val directAction: Action = {
            startToChooseFile()
        }

        val rejectAction: Action = {
            stopChoosingFile()
            showMessageForPermissionDenied()
        }

        helper.verifyPermissionAndRun(
            directAction = directAction,
            rejectedAction = rejectAction
        )
    }

    private fun startToChooseFile() {
        val params = this.params ?: return
        chooserLauncher.launch(params)
    }

    private fun stopChoosingFile() {
        this.callback?.onReceiveValue(null)
        clearReferences()
    }

    private fun clearReferences() {
        this.callback = null
        this.params = null
    }

    private fun showMessageForPermissionDenied() {
        val context = hostFragment.context ?: return
        Toast.makeText(context, R.string.permission_toast_storage_deny, Toast.LENGTH_LONG).show()
    }

    class FilePickerContract : ActivityResultContract<FileChooserParams, Uri?>() {
        @CallSuper
        override fun createIntent(
            context: Context,
            params: FileChooserParams
        ): Intent {
            val mimeTypes = params.acceptTypes
            val title =
                if (TextUtils.isEmpty(params.title)) context.getString(R.string.file_picker_title)
                else params.title

            return FilePickerUtil.getFilePickerIntent(context, title, mimeTypes)
        }

        override fun getSynchronousResult(
            context: Context,
            params: FileChooserParams
        ): SynchronousResult<Uri?>? {
            return null
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            return if (intent == null || resultCode != Activity.RESULT_OK) null else intent.data
        }
    }
}
