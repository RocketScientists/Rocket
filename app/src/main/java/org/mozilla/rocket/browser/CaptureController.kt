package org.mozilla.rocket.browser

import android.Manifest
import android.os.Handler
import android.webkit.WebView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.mozilla.focus.BuildConfig
import org.mozilla.focus.R
import org.mozilla.focus.activity.MainActivity
import org.mozilla.focus.fragment.ScreenCaptureDialogFragment
import org.mozilla.focus.screenshot.CaptureRunnable
import org.mozilla.focus.utils.Settings
import org.mozilla.rocket.chrome.ChromeViewModel.ScreenCaptureTelemetryData
import org.mozilla.rocket.landing.PortraitComponent
import org.mozilla.rocket.landing.PortraitStateModel
import org.mozilla.rocket.permission.Action
import org.mozilla.rocket.permission.PermissionHelper
import java.lang.ref.WeakReference

private const val TAG_CAPTURE_FRAGMENT = "capturingFragment"
private const val CAPTURE_WAIT_INTERVAL = 150L

class CaptureController(private val hostFragment: Fragment) : DefaultLifecycleObserver {

    private lateinit var helper: PermissionHelper

    private var isPendingCaptureRequest: Boolean = false
    private var refWebView: WeakReference<WebView>? = null
    private var refTelemetryData: WeakReference<ScreenCaptureTelemetryData?>? = null
    private var refCallback: WeakReference<SuccessPromotionCallback?>? = null

    // onCreateFragment
    override fun onCreate(owner: LifecycleOwner) {
        helper = PermissionHelper.createHelperOnCreateStage(
            hostFragment,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            R.string.permission_toast_storage
        )
    }

    // onDestroyFragment
    override fun onDestroy(owner: LifecycleOwner) {
        clearReferences()
    }

    // onResumeFragment
    override fun onResume(owner: LifecycleOwner) {
        if (isPendingCaptureRequest) {
            restartCapture()
        }
        isPendingCaptureRequest = false
    }

    fun capture(
        webView: WebView,
        telemetryData: ScreenCaptureTelemetryData? = null,
        callback: SuccessPromotionCallback? = null
    ) {
        refWebView = WeakReference(webView)
        refTelemetryData = WeakReference(telemetryData)
        refCallback = WeakReference(callback)

        val directAction: Action = {
            clearReferences()
            startCapture(webView, telemetryData, callback)
        }

        val grantedAction: Action = {
            // Permission granted. Set this flag to true, to complete capture task in next
            // hostFragment.onResume.
            isPendingCaptureRequest = true
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

    private fun startCapture(
        webView: WebView,
        telemetryData: ScreenCaptureTelemetryData? = null,
        callback: SuccessPromotionCallback? = null
    ) {
        if (!hostFragment.isResumed) {
            return
        }
        val activity = hostFragment.activity ?: return
        clearReferences()

        val capturingFragment = createCaptureScreen()
        capturingFragment.show(hostFragment.childFragmentManager, TAG_CAPTURE_FRAGMENT)

        val runnable = CaptureRunnable(
            activity,
            webView,
            capturingFragment,
            telemetryData
        ) { isSuccess ->
            when (getFinishAction(isSuccess)) {
                FinishAction.NOTHING -> Unit
                FinishAction.TOAST -> showScreenshotSavedToast(isSuccess)
                FinishAction.FRAGMENT_PROMOTION -> callback?.showPromotionOnSuccess()
            }
        }

        // Post delay to wait for Dialog to show
        Handler().postDelayed(runnable, CAPTURE_WAIT_INTERVAL)
    }

    private fun clearReferences() {
        refWebView?.clear()
        refTelemetryData?.clear()
        refCallback?.clear()
    }

    private fun restartCapture() {
        val webView = refWebView?.get() ?: return
        val telemetryData = refTelemetryData?.get()
        val callback = refCallback?.get()
        clearReferences()
        startCapture(webView, telemetryData, callback)
    }

    private fun showMessageForPermissionDenied() {
        val context = hostFragment.context ?: return
        Toast.makeText(context, R.string.permission_toast_storage_deny, Toast.LENGTH_LONG).show()
    }

    private fun createCaptureScreen(): ScreenCaptureDialogFragment {
        val capturingFragment = ScreenCaptureDialogFragment.newInstance()
        val portraitState = getPortraitStateModel(hostFragment)
        if (portraitState != null) {
            portraitState.request(PortraitComponent.ScreenCapture)
            capturingFragment.addOnDismissListener {
                portraitState.cancelRequest(PortraitComponent.ScreenCapture)
            }
        }
        return capturingFragment
    }

    private fun getPortraitStateModel(fragment: Fragment): PortraitStateModel? {
        val activity = fragment.activity ?: return null
        return if (activity is MainActivity) {
            activity.portraitStateModel
        } else {
            if (BuildConfig.DEBUG) {
                throw IllegalStateException("Only MainActivity has portrait state model")
            }
            null
        }
    }

    private fun showScreenshotSavedToast(success: Boolean) {
        val context = hostFragment.context ?: return
        val toastMsgId = if (success) R.string.screenshot_saved else R.string.screenshot_failed
        Toast.makeText(context, toastMsgId, Toast.LENGTH_SHORT).show()
    }

    private fun getFinishAction(success: Boolean): FinishAction {
        val context = hostFragment.context ?: return FinishAction.NOTHING

        val eventHistory = Settings.getInstance(context).eventHistory
        val isNotShowMyShot = eventHistory.contains(Settings.Event.ShowMyShotOnBoardingDialog)
        if (success && isNotShowMyShot) {
            return FinishAction.FRAGMENT_PROMOTION
        }

        return FinishAction.TOAST
    }

    private enum class FinishAction {
        NOTHING,
        TOAST,
        FRAGMENT_PROMOTION
    }

    fun interface SuccessPromotionCallback {
        fun showPromotionOnSuccess()
    }
}
