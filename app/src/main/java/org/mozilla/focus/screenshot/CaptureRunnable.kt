package org.mozilla.focus.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import org.mozilla.focus.R
import org.mozilla.focus.fragment.ScreenCaptureDialogFragment
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.Settings
import org.mozilla.rocket.browser.BrowserFragment
import org.mozilla.rocket.chrome.ChromeViewModel.ScreenCaptureTelemetryData
import java.lang.ref.WeakReference

class CaptureRunnable(
    context: Context,
    browserFragment: BrowserFragment,
    screenCaptureDialogFragment: ScreenCaptureDialogFragment,
    container: View,
    telemetryData: ScreenCaptureTelemetryData?
) : ScreenshotCaptureTask(context, telemetryData), Runnable, BrowserFragment.ScreenshotCallback {
    private val refContext = WeakReference(context)
    private val refBrowserFragment = WeakReference(browserFragment)
    private val refScreenCaptureDialogFragment = WeakReference(screenCaptureDialogFragment)
    private val refContainerView = WeakReference(container)

    override fun run() {
        val browserFragment = refBrowserFragment.get() ?: return
        if (browserFragment.capturePage(this)) {
            //  onCaptureComplete called
        } else {
            //  Capture failed
            val screenCaptureDialogFragment = refScreenCaptureDialogFragment.get()
            screenCaptureDialogFragment?.dismiss()
            promptScreenshotResult(false)
        }
    }

    override fun onCaptureComplete(title: String?, url: String?, bitmap: Bitmap?) {
        val context = refContext.get() ?: return
        execute(title, url, bitmap)
    }

    override fun onPostExecute(path: String) {
        val screenCaptureDialogFragment = refScreenCaptureDialogFragment.get()
        if (screenCaptureDialogFragment == null) {
            cancel(true)
            return
        }
        val captureSuccess = !TextUtils.isEmpty(path)
        if (captureSuccess) {
            Settings.getInstance(refContext.get()).setHasUnreadMyShot(true)
        }
        promptScreenshotResult(captureSuccess)
        if (TextUtils.isEmpty(path)) {
            screenCaptureDialogFragment.dismiss()
        } else {
            screenCaptureDialogFragment.dismiss(!AppConstants.isUnderEspressoTest())
        }
    }

    private fun promptScreenshotResult(success: Boolean) {
        val context = refContext.get() ?: return
        val browserFragment = refBrowserFragment.get()

        browserFragment?.captureStateListener?.onPromptScreenshotResult()

        val eventHistory = Settings.getInstance(context).eventHistory
        val isNotShowMyShot = eventHistory.contains(Settings.Event.ShowMyShotOnBoardingDialog)
        if (browserFragment != null && success && isNotShowMyShot) {
            // My shot on boarding didn't show before and capture is succeed, skip to show toast
            browserFragment.checkToShowMyShotOnBoarding()
            return
        }

        val toastMsgId = if (success) R.string.screenshot_saved else R.string.screenshot_failed
        Toast.makeText(context, toastMsgId, Toast.LENGTH_SHORT).show()
    }

    interface CaptureStateListener {
        fun onPromptScreenshotResult()
    }
}
