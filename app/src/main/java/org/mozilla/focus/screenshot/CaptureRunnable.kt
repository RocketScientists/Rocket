package org.mozilla.focus.screenshot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.TextUtils
import android.util.DisplayMetrics
import android.webkit.WebView
import org.mozilla.focus.fragment.ScreenCaptureDialogFragment
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.Settings
import org.mozilla.rocket.chrome.ChromeViewModel.ScreenCaptureTelemetryData
import java.lang.ref.WeakReference

class CaptureRunnable(
    activity: Activity,
    webView: WebView,
    screenCaptureDialogFragment: ScreenCaptureDialogFragment,
    telemetryData: ScreenCaptureTelemetryData?,
    captureResultCallback: CaptureResultCallback?
) : ScreenshotCaptureTask(activity, telemetryData), Runnable {
    private val refActivity = WeakReference(activity)
    private val refWebView = WeakReference(webView)
    private val refScreenCaptureDialogFragment = WeakReference(screenCaptureDialogFragment)
    private val refCallback = WeakReference(captureResultCallback)

    override fun run() {
        val activity = refActivity.get() ?: return
        val webView = refWebView.get() ?: return
        if (capturePage(activity, webView)) {
            //  onCaptureComplete called
        } else {
            //  Capture failed
            val screenCaptureDialogFragment = refScreenCaptureDialogFragment.get()
            screenCaptureDialogFragment?.dismiss()
            refCallback.get()?.onCaptureResult(false)
        }
    }

    private fun capturePage(activity: Activity, webView: WebView): Boolean {
        val content = getPageBitmap(activity, webView) ?: return false // Failed to capture
        val title = webView.title ?: "Empty title"
        val url = webView.url ?: "Empty url"
        onCaptureComplete(title, url, content)
        return true
    }

    private fun getPageBitmap(activity: Activity, webView: WebView): Bitmap? {
        val displayMetrics = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
        return try {
            val height = (webView.contentHeight * displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(webView.width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
            // OOM may occur, even if OOMError is not thrown, operations during Bitmap creation may
            // throw other Exceptions such as NPE when the bitmap is very large.
        } catch (ex: Exception) {
            null
        } catch (ex: OutOfMemoryError) {
            null
        }
    }

    private fun onCaptureComplete(title: String, url: String, bitmap: Bitmap?) {
        refCallback.get()?.onCaptureResult(true)
        // pass bitmap to ScreenshotCaptureTask for saving image
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
            Settings.getInstance(refActivity.get()).setHasUnreadMyShot(true)
        }

        refCallback.get()?.onCaptureResult(captureSuccess)
        if (TextUtils.isEmpty(path)) {
            screenCaptureDialogFragment.dismiss()
        } else {
            screenCaptureDialogFragment.dismiss(!AppConstants.isUnderEspressoTest())
        }
    }

    fun interface CaptureResultCallback {
        fun onCaptureResult(success: Boolean)
    }
}
