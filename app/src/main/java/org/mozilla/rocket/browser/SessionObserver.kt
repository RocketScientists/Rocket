package org.mozilla.rocket.browser

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import mozilla.components.browser.session.Download
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.rocket.history.SessionHistoryInserter
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.TabView
import org.mozilla.rocket.tabs.TabViewClient
import org.mozilla.rocket.tabs.TabViewEngineSession
import org.mozilla.rocket.tabs.web.Download as RocketDownload

class SessionObserver(
    private val sessionCtrl: SessionController
) : Session.Observer, TabViewEngineSession.Client {

    private var observingSession: Session? = null

    private var historyInserter: SessionHistoryInserter? = SessionHistoryInserter()

    // Some url may report progress from 0 again for the same url. filter them out to avoid
    // progress bar regression when scrolling.
    override fun onLoadingStateChanged(session: Session, loading: Boolean) {
        if (loading) {
            historyInserter?.onTabStarted(session)
        } else {
            sessionCtrl.chromeGetUrl()?.let {
                historyInserter?.onTabFinished(session, it)
            }
        }
        if (session.isFocusing()) {
            sessionCtrl.chromeUpdateLoadingState(session, loading)
        }
    }

    override fun onSecurityChanged(session: Session, isSecure: Boolean) {
        if (session.isFocusing()) {
            sessionCtrl.chromeUpdateSiteIdentity(isSecure)
        }
    }

    override fun onUrlChanged(session: Session, url: String?) {
        if (session.isFocusing()) {
            sessionCtrl.chromeChangeUrlOfFocusedSession(url)
        }
    }

    override fun handleExternalUrl(url: String?): Boolean {
        if (url != null) {
            return sessionCtrl.chromeHandleExternalUrl(url)
        }
        return false
    }

    override fun updateFailingUrl(url: String?, updateFromError: Boolean) {
        val observing = observingSession ?: return
        historyInserter?.updateFailingUrl(observing, url, updateFromError)
    }

    override fun onProgress(session: Session, progress: Int) {
        if (session.isFocusing()) {
            sessionCtrl.chromeUpdateProgress(session, progress)
        }
    }

    override fun onShowFileChooser(
        es: TabViewEngineSession,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (!observingSession.isFocusing()) {
            return false
        }
        TelemetryWrapper.browseFilePermissionEvent()
        return try {
            requireNotNull(filePathCallback)
            requireNotNull(fileChooserParams)
            sessionCtrl.chromeChooseFile(filePathCallback, fileChooserParams)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun onTitleChanged(session: Session, title: String?) {
        if (session.isFocusing()) {
            sessionCtrl.chromeChangeFocusedSessionTitle(title)
        }
    }

    override fun onReceivedIcon(icon: Bitmap?) = Unit

    override fun onLongPress(session: Session, hitTarget: TabView.HitTarget) {
        if (session.isFocusing()) {
            sessionCtrl.chromeShowLinkContextMenu(hitTarget)
        }
    }

    override fun onEnterFullScreen(callback: TabView.FullscreenCallback, view: View?) {
        if (observingSession == null) {
            return
        }
        if (!observingSession.isFocusing()) {
            callback.fullScreenExited()
            return
        }
        if (observingSession?.engineSession?.tabView != null && view != null) {
            sessionCtrl.chromeEnterFullScreen(callback, view)
        }
    }

    override fun onExitFullScreen() {
        if (observingSession == null) {
            return
        }
        sessionCtrl.chromeExitFullScreen()
    }

    fun clearFocusFromObservingSession() {
        // WebView gets focus, but unable to open the keyboard after exit Fullscreen for Android 7.0+
        // We guess some component in WebView might lock focus
        // So when user touches the input text box on WebView, it will not trigger to open the keyboard
        // It may be a WebView bug.
        // The workaround is clearing WebView focus
        // The WebView will be normal when it gets focus again.
        // If android change behavior after, can remove this.
        observingSession?.engineSession?.tabView?.let {
            if (it is WebView) {
                it.clearFocus()
            }
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback?
    ) {
        if (callback == null) {
            return
        }
        if (observingSession.isFocusing()) {
            sessionCtrl.chromeShowGeolocationPermission(origin, callback)
        }
    }

    fun disableHistoryInsertion() {
        historyInserter = null
    }

    fun changeObservingSession(nextSession: Session?) {
        observingSession?.unregister(this)
        observingSession?.engineSession?.engineSessionClient = null
        observingSession = nextSession?.also {
            it.register(this)
            it.engineSession?.engineSessionClient = this
        }
    }

    override fun onFindResult(
        session: Session,
        result: mozilla.components.browser.session.Session.FindResult
    ) {
        if (session.isFocusing()) {
            sessionCtrl.chromeSetReceivedFindResult(result)
        }
    }

    override fun onDownload(session: Session, download: Download): Boolean {
        val rocketDownload = RocketDownload(
            download.url,
            download.fileName,
            download.userAgent,
            "",
            download.contentType,
            requireNotNull(download.contentLength),
            false
        )
        return sessionCtrl.chromeQueueDownload(rocketDownload)
    }

    override fun onNavigationStateChanged(
        session: Session,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        if (session.isFocusing()) {
            sessionCtrl.chromeChangeNavigationState(canGoBack, canGoForward)
        }
    }

    override fun onHttpAuthRequest(
        callback: TabViewClient.HttpAuthCallback,
        host: String?,
        realm: String?
    ) {
        if (host != null && realm != null) {
            sessionCtrl.chromeShowHttpAuth(callback, host, realm)
        }
    }

    private fun Session?.isFocusing(): Boolean {
        return sessionCtrl.getFocusSession() == this
    }
}
