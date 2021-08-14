package org.mozilla.rocket.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.utils.IntentUtils
import org.mozilla.focus.web.HttpAuthenticationDialogBuilder
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.extension.thenRun
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.TabView
import org.mozilla.rocket.tabs.TabViewClient
import org.mozilla.rocket.tabs.TabsSessionProvider
import org.mozilla.rocket.tabs.utils.TabUtil
import org.mozilla.rocket.tabs.web.Download
import org.mozilla.threadutils.ThreadUtils
import mozilla.components.browser.session.Session.FindResult as MozillaFindResult

private const val BUNDLE_MAX_SIZE = 300 * 1000 // 300K

/**
 * SessionController is a bridge between BrowserFragment and Session's observer.
 *
 *   +--------------------+     +-------------------+    +----------------------+
 *   |  chromeViewModel   |<--->|                   |<-->|SessionManagerObserver|
 *   +----------^---------+     |                   |    +----------------------+
 *              |               | SessionController |    +----------------------+
 *   +----------v---------+     |                   |<-->|   SessionObserver    |
 *   |  BrowserFragment   |<--->|                   |    |                      |
 *   +--------------------+     +-------------------+    +----------------------+
 *
 * BrowserFragment use SessionController to operate session indirectly. SessionController accepts
 * changes from Observers, then update BrowserFragment directly, or update ChromeViewModel.
 *
 */
class SessionController(private val browserFragment: BrowserFragment) : LifecycleObserver {

    private lateinit var chromeViewModel: ChromeViewModel

    private lateinit var sessionManager: SessionManager
    private val sessionObserver = SessionObserver(this)
    private val managerObserver = SessionManagerObserver(this, isStartedFromExternalApp())

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreateFragment() {
        chromeViewModel = browserFragment.chromeViewModel
        sessionManager = TabsSessionProvider.getOrThrow(browserFragment.requireActivity())
        sessionManager.register(managerObserver)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroyFragment() {
        sessionManager.unregister(managerObserver)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onResume() {
        sessionManager.resume()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onPause() {
        sessionManager.pause()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onStop() {
        if (browserFragment.isSystemUiChanged()) {
            chromeExitFullScreen()
        }
    }

    fun stopLoadingTabs() {
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.stopLoading()
        }
    }

    fun reloadingTabs() {
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.reload()
        }
    }

    fun chromeEnterFullScreen(callback: TabView.FullscreenCallback, view: View) {
        browserFragment.enterFullScreen(callback, view)
    }

    fun chromeExitFullScreen() {
        browserFragment.exitFullScreen()
        sessionManager.focusSession?.engineSession?.tabView?.performExitFullScreen()
        sessionObserver.clearFocusFromObservingSession()
    }

    fun maybeRestoreWebViewState(savedInstanceState: Bundle?) {
        val savedState = savedInstanceState ?: return
        // FIXME: Obviously, only restore current tab is not enough
        val focusTab = sessionManager.focusSession ?: return
        val tabView = focusTab.engineSession?.tabView
        if (tabView != null) {
            tabView.restoreViewState(savedState)
        } else {
            // Focus to tab again to force initialization.
            sessionManager.switchToTab(focusTab.id)
        }
    }

    fun saveViewStateFromFocusedSession(outState: Bundle) {
        sessionManager.focusSession?.engineSession?.tabView?.saveViewState(outState)
        // Workaround for #1107 TransactionTooLargeException
        // since Android N, system throws a exception rather than just a warning(then drop bundle)
        // To set a threshold for dropping WebView state manually
        // refer: https://issuetracker.google.com/issues/37103380
        val key = "WEB_VIEW_CHROMIUM_STATE"
        if (outState.containsKey(key)) {
            val size = outState.getByteArray(key)?.size ?: -1
            if (size > BUNDLE_MAX_SIZE) {
                outState.remove(key)
            }
        }
    }

    fun maybeGoForward() {
        canGoForward().thenRun { goForward() }
    }

    fun maybeGoBack() {
        canGoBack().thenRun { goBack() }
    }

    fun reload() {
        sessionManager.focusSession?.engineSession?.tabView?.reload()
    }

    fun stop() {
        sessionManager.focusSession?.engineSession?.tabView?.stopLoading()
    }

    fun getFocusSession(): Session? {
        return sessionManager.focusSession
    }

    fun handleBackKey(): Boolean {
        if (canGoBack()) {
            // Go back in web history
            goBack()
        } else {
            val focus = sessionManager.focusSession ?: return false
            if (focus.isFromExternal || focus.hasParentTab()) {
                sessionManager.closeTab(focus.id)
            } else {
                ScreenNavigator[browserFragment.context].popToHomeScreen(true)
            }
        }
        return true
    }

    fun switchToTab(tabId: String) {
        if (tabId.isNotBlank()) {
            sessionManager.switchToTab(tabId)
        }
    }

    fun loadUrl(
        url: String,
        openNewTab: Boolean,
        isFromExternal: Boolean,
        onViewReadyCallback: Runnable?
    ) {
        if (openNewTab) {
            sessionManager.addTab(url, TabUtil.argument(null, isFromExternal, true))
            // In case we call SessionManager#addTab(), which is an async operation calls back in the next
            // message loop. By posting this runnable we can call back in the same message loop with
            // TabsContentListener#onFocusChanged(), which is when the view is ready and being attached.
            ThreadUtils.postToMainThread(onViewReadyCallback)
        } else {
            val currentTab = sessionManager.focusSession?.engineSession?.tabView
            if (currentTab != null) {
                currentTab.loadUrl(url)
                onViewReadyCallback?.run()
            } else {
                sessionManager.addTab(url, TabUtil.argument(null, isFromExternal, true))
                ThreadUtils.postToMainThread(onViewReadyCallback)
            }
        }
    }

    fun setContentBlockingEnabled(enabled: Boolean) {
        // TODO: Better if we can move this logic to some setting-like classes, and provider interface
        // for configuring blocking function of each tab.
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.setContentBlockingEnabled(enabled)
        }
    }

    fun setImageBlockingEnabled(enabled: Boolean) {
        // TODO: Better if we can move this logic to some setting-like classes, and provider interface
        // for configuring blocking function of each tab.
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.setImageBlockingEnabled(enabled)
        }
    }

    fun setJavaScriptBlockingEnabled(enabled: Boolean) {
        // TODO: Better if we can move this logic to some setting-like classes, and provider interface
        // for configuring JavaScript blocking function of each tab.
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.setJavaScriptBlockingEnabled(enabled)
        }
    }

    fun chromePopToHomeScreen() {
        ScreenNavigator[browserFragment.context].popToHomeScreen(true)
    }

    fun chromeFinishActivity() {
        browserFragment.activity?.finish()
    }

    fun chromeUpdateSiteIdentity(isSecure: Boolean) {
        chromeViewModel.isCurrentSessionSecure.value = isSecure
    }

    fun chromeChangeUrlOfFocusedSession(url: String?) {
        chromeViewModel.onFocusedUrlChanged(url)
        // Prevent updateURL when directly entering URL in the address bar.
        val chromeOpenUrl = chromeViewModel.openUrl.value?.url.orEmpty()
        if (chromeOpenUrl != url) {
            browserFragment.updateChromeUrl(url)
        } else if (chromeOpenUrl.isNotEmpty()) {
            chromeViewModel.openUrl.value!!.url = ""
        }
    }

    fun chromeTransitToTab(focusSession: Session) {
        val tabView = focusSession.engineSession?.tabView?.getView()
            ?: throw RuntimeException("Tabview should be created at this moment and never be null")

        // ensure the session has not been attached to another parent earlier.
        focusSession.engineSession?.detach()
        browserFragment.transitToTab(tabView)

        sessionObserver.changeObservingSession(focusSession)

        chromeViewModel.onFocusedUrlChanged(focusSession.url)
        chromeViewModel.onFocusedTitleChanged(focusSession.title)
        browserFragment.refreshChrome(focusSession)
        chromeViewModel.onNavigationStateChanged(canGoBack(), canGoForward())
    }

    fun chromePromoteAddedSession(session: Session) {
        val sessionId = session.id
        browserFragment.showSnackBarForAddedSession {
            sessionManager.switchToTab(sessionId)
        }
    }

    fun chromeUpdateTabCount(count: Int) {
        chromeViewModel.onTabCountChanged(count)
    }

    fun chromeQueueDownload(download: Download): Boolean {
        val activity = browserFragment.activity ?: return false
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return false
        }
        browserFragment.maybeQueueDownload(download)
        return true
    }

    fun chromeHandleExternalUrl(url: String): Boolean {
        val context = browserFragment.context
        if (context == null) {
            val msg = "No context to use, abort callback handleExternalUrl"
            Log.w(ScreenNavigator.BROWSER_FRAGMENT_TAG, msg)
            return false
        }
        val navigationState = chromeViewModel.navigationState.value
        if (navigationState != null && navigationState.isHome) {
            val msg = "Ignore external url when browser page is not on the front"
            Log.w(ScreenNavigator.BROWSER_FRAGMENT_TAG, msg)
            return false
        }

        return IntentUtils.handleExternalUri(context, url)
    }

    fun chromeChooseFile(
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: WebChromeClient.FileChooserParams
    ) {
        browserFragment.chooseFile(filePathCallback, fileChooserParams)
    }

    fun chromeChangeFocusedSessionTitle(title: String?) {
        chromeViewModel.onFocusedTitleChanged(title)
    }

    fun chromeShowLinkContextMenu(hitTarget: TabView.HitTarget) {
        val activity = browserFragment.activity
        if (activity == null) {
            val msg = "No context to use, abort callback onLongPress"
            Log.w(ScreenNavigator.BROWSER_FRAGMENT_TAG, msg)
            return
        }
        browserFragment.showContextMenu(hitTarget)
    }

    fun chromeShowGeolocationPermission(origin: String, callback: GeolocationPermissions.Callback) {
        browserFragment.maybeShowGeolocationPermission(origin, callback)
    }

    fun chromeSetReceivedFindResult(result: MozillaFindResult) {
        browserFragment.setReceivedFindResult(result)
    }

    fun chromeChangeNavigationState(canGoBack: Boolean, canGoForward: Boolean) {
        chromeViewModel.onNavigationStateChanged(canGoBack, canGoForward)
    }

    fun chromeShowHttpAuth(callback: TabViewClient.HttpAuthCallback, host: String, realm: String) {
        // TODO: too complicated. should be refactor
        val activity = browserFragment.activity ?: return
        val innerBuilder = HttpAuthenticationDialogBuilder.Builder(activity, host, realm)
        innerBuilder.setOkListener { _: String?, _: String?, username: String?, password: String? ->
            callback.proceed(username, password)
        }
        innerBuilder.setCancelListener { callback.cancel() }
        val builder = innerBuilder.build()
        builder.createDialog()
        builder.show()
    }

    fun chromeGetUrl(): String = browserFragment.chromeUrl

    fun chromeUpdateProgress(session: Session, progress: Int) {
        browserFragment.updateProgressOfSession(session, progress)
    }

    fun chromeUpdateLoadingState(session: Session, isLoading: Boolean) {
        if (isLoading) {
            chromeViewModel.onPageLoadingStarted()
            browserFragment.updateChromeUrl(session.url)
        } else {
            // The URL which is supplied in onTabFinished() could be fake (see #301), but webView's
            // URL is always correct _except_ for error pages
            updateUrlFromWebView()
            chromeViewModel.onPageLoadingStopped()
        }
    }

    private fun updateUrlFromWebView() {
        if (sessionManager.focusSession != null) {
            val viewURL = sessionManager.focusSession?.url
            chromeChangeUrlOfFocusedSession(viewURL)
        }
    }

    private fun canGoForward(): Boolean = sessionManager.focusSession?.canGoForward == true

    private fun canGoBack(): Boolean = sessionManager.focusSession?.canGoBack == true

    private fun goBack() {
        val currentTab = sessionManager.focusSession?.engineSession?.tabView ?: return
        // The Session.canGoBack property is mainly for UI display purpose and is only sampled
        // at onNavigationStateChange which is called at onPageFinished, onPageStarted and
        // onReceivedTitle. We do some sanity check here.
        if (!currentTab.canGoBack()) {
            return
        }
        currentTab.goBack()
        (currentTab as? WebView)?.originalUrl?.let {
            browserFragment.loadedUrl = it
        }
    }

    private fun goForward() {
        val currentTab = sessionManager.focusSession?.engineSession?.tabView ?: return
        currentTab.goForward()
        (currentTab as? WebView)?.originalUrl?.let {
            browserFragment.loadedUrl = it
        }
    }

    private fun isStartedFromExternalApp(): Boolean {
        // No SafeIntent needed here because intent.getAction() is safe (SafeIntent simply calls
        // intent.getAction() without any wrapping):
        val intent = browserFragment.activity?.intent ?: return false
        val isInternal = intent.getBooleanExtra(IntentUtils.EXTRA_IS_INTERNAL_REQUEST, false)
        return !isInternal && Intent.ACTION_VIEW == intent.action
    }
}
