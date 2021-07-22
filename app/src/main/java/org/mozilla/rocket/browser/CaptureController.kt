package org.mozilla.rocket.browser

import android.os.Handler
import org.mozilla.focus.BuildConfig
import org.mozilla.focus.R
import org.mozilla.focus.activity.MainActivity
import org.mozilla.focus.fragment.ScreenCaptureDialogFragment
import org.mozilla.focus.screenshot.CaptureRunnable
import org.mozilla.rocket.chrome.ChromeViewModel.ScreenCaptureTelemetryData
import org.mozilla.rocket.landing.PortraitComponent
import org.mozilla.rocket.landing.PortraitStateModel

class CaptureController(val browserFragment: BrowserFragment) {

    private var hasPendingScreenCaptureTask = false
    private var pendingScreenCaptureTelemetryData: ScreenCaptureTelemetryData? = null

    private val portraitStateModel: PortraitStateModel?
        get() {
            val activity = browserFragment.activity ?: return null
            return if (activity is MainActivity) {
                activity.portraitStateModel
            } else {
                if (BuildConfig.DEBUG) {
                    throw IllegalStateException("Only MainActivity has portrait state model")
                } else {
                    null
                }
            }
        }

    fun onResume() {
        if (hasPendingScreenCaptureTask) {
            startCapture(pendingScreenCaptureTelemetryData)
            clearPendingScreenCaptureTask()
        }
    }

    fun startCapture(telemetryData: ScreenCaptureTelemetryData? = null) {
        if (!browserFragment.isResumed) {
            return
        }
        val context = browserFragment.context ?: return
        clearPendingScreenCaptureTask()
        val capturingFragment = ScreenCaptureDialogFragment.newInstance()
        val portraitState = portraitStateModel
        if (portraitState != null) {
            portraitState.request(PortraitComponent.ScreenCapture)
            capturingFragment.addOnDismissListener {
                portraitState.cancelRequest(PortraitComponent.ScreenCapture)
            }
        }
        capturingFragment.show(browserFragment.childFragmentManager, "capturingFragment")
        // Post delay to wait for Dialog to show
        Handler().postDelayed(
            CaptureRunnable(
                browserFragment.requireContext(),
                browserFragment,
                capturingFragment,
                browserFragment.requireActivity().findViewById(R.id.container),
                telemetryData
            ),
            BrowserFragment.CAPTURE_WAIT_INTERVAL.toLong()
        )
    }

    fun setPendingScreenCaptureTask(telemetryData: ScreenCaptureTelemetryData) {
        hasPendingScreenCaptureTask = true
        pendingScreenCaptureTelemetryData = telemetryData
    }

    private fun clearPendingScreenCaptureTask() {
        hasPendingScreenCaptureTask = false
        pendingScreenCaptureTelemetryData = null
    }
}
