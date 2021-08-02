package org.mozilla.rocket.browser

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import org.mozilla.focus.menu.WebContextMenu
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.FileChooseAction
import org.mozilla.focus.utils.IntentUtils
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.web.HttpAuthenticationDialogBuilder
import org.mozilla.rocket.download.BrowserDownloadCallback
import org.mozilla.rocket.history.SessionHistoryInserter
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.TabView
import org.mozilla.rocket.tabs.TabViewClient
import org.mozilla.rocket.tabs.TabViewEngineSession
import org.mozilla.rocket.tabs.web.Download

class SessionObserver(
    private val browserFragment: BrowserFragment
) : Session.Observer, TabViewEngineSession.Client {
    private var session: Session? = null
    private val historyInserter = SessionHistoryInserter()

    // Some url may report progress from 0 again for the same url. filter them out to avoid
    // progress bar regression when scrolling.
    override fun onLoadingStateChanged(session: Session, loading: Boolean) {
        browserFragment.isLoading = loading
        if (loading) {
            historyInserter.onTabStarted(session)
        } else {
            historyInserter.onTabFinished(session, browserFragment.url)
        }
        if (!isForegroundSession(session)) {
            return
        }
        if (loading) {
            browserFragment.loadedUrl = null
            browserFragment.chromeViewModel.onPageLoadingStarted()
            browserFragment.updateIsLoading(true)
            browserFragment.updateURL(session.url)
            browserFragment.appBarBgTransition.resetTransition()
            browserFragment.statusBarBgTransition.resetTransition()
        } else {
            // The URL which is supplied in onTabFinished() could be fake (see #301), but webview's
            // URL is always correct _except_ for error pages
            updateUrlFromWebView(session)
            browserFragment.chromeViewModel.onPageLoadingStopped()
            browserFragment.updateIsLoading(false)
            browserFragment.appBarBgTransition.startTransition(BrowserFragment.ANIMATION_DURATION)
            browserFragment.statusBarBgTransition.startTransition(BrowserFragment.ANIMATION_DURATION)
        }
    }

    override fun onSecurityChanged(session: Session, isSecure: Boolean) {
        val level = if (isSecure) BrowserFragment.SITE_LOCK else BrowserFragment.SITE_GLOBE
        browserFragment.binding?.toolbar?.siteIdentity?.setImageLevel(level)
    }

    override fun onUrlChanged(session: Session, url: String?) {
        browserFragment.chromeViewModel.onFocusedUrlChanged(url)
        if (!isForegroundSession(session)) {
            return
        }
        // Prevent updateURL when directly entering URL in the address bar.
        if (browserFragment.chromeViewModel.openUrl.value?.url ?: "" != url) {
            browserFragment.updateURL(url)
        } else if (browserFragment.chromeViewModel.openUrl.value?.url ?: "" != "") {
            browserFragment.chromeViewModel.openUrl.value!!.url = ""
        }
    }

    override fun handleExternalUrl(url: String?): Boolean {
        if (browserFragment.context == null) {
            Log.w(
                ScreenNavigator.BROWSER_FRAGMENT_TAG,
                "No context to use, abort callback handleExternalUrl"
            )
            return false
        }
        val navigationState = browserFragment.chromeViewModel.navigationState.value
        if (navigationState != null && navigationState.isHome) {
            val msg = "Ignore external url when browser page is not on the front"
            Log.w(ScreenNavigator.BROWSER_FRAGMENT_TAG, msg)
            return false
        }
        return IntentUtils.handleExternalUri(browserFragment.context, url)
    }

    override fun updateFailingUrl(url: String?, updateFromError: Boolean) {
        session?.let {
            historyInserter.updateFailingUrl(it, url, updateFromError)
        }
    }

    // Remove URL fragment to prevent progress bar update when location.hash change (follow Chrome and Firefox for Android behavior)
    private fun removeUrlFragment(url: String): String {
        val endPos: Int = when {
            url.indexOf("#") > 0 -> url.indexOf("#")
            else -> url.length
        }
        return url.substring(0, endPos)
    }

    override fun onProgress(session: Session, progress: Int) {
        if (!isForegroundSession(session)) {
            return
        }
        browserFragment.hideFindInPage()
        if (browserFragment.sessionManager.focusSession != null) {
            val currentUrl = browserFragment.sessionManager.focusSession?.url
            val progressIsForLoadedUrl =
                TextUtils.equals(
                    currentUrl?.let { removeUrlFragment(it) },
                    browserFragment.loadedUrl?.let { removeUrlFragment(it) }
                )
            // Some new url may give 100 directly and then start from 0 again. don't treat
            // as loaded for these urls;
            val progressBar = browserFragment.binding?.progressBar
            val urlBarLoadingToFinished = if (progressBar == null) {
                false
            } else {
                progressBar.max != progressBar.progress && progress == progressBar.max
            }
            if (urlBarLoadingToFinished) {
                browserFragment.loadedUrl = currentUrl
            }
            // Some URL cause progress bar to stuck at loading state,
            // allowing progress update to progressBar.max solve the issue
            if (progressIsForLoadedUrl && progress != progressBar?.max) {
                return
            }
        }
        browserFragment.binding?.progressBar?.progress = progress
    }

    override fun onShowFileChooser(
        es: TabViewEngineSession,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (!isForegroundSession(session)) {
            return false
        }
        TelemetryWrapper.browseFilePermissionEvent()
        return try {
            requireNotNull(filePathCallback)
            requireNotNull(fileChooserParams)
            browserFragment.fileChooseAction =
                FileChooseAction(browserFragment, filePathCallback, fileChooserParams)
            browserFragment.permissionHandler.tryAction(
                browserFragment,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                BrowserFragment.ACTION_PICK_FILE,
                null
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun onTitleChanged(session: Session, title: String?) {
        browserFragment.chromeViewModel.onFocusedTitleChanged(title)
    }

    override fun onReceivedIcon(icon: Bitmap?) {}
    override fun onLongPress(session: Session, hitTarget: TabView.HitTarget) {
        if (browserFragment.activity == null) {
            Log.w(
                ScreenNavigator.BROWSER_FRAGMENT_TAG,
                "No context to use, abort callback onLongPress"
            )
            return
        }
        browserFragment.webContextMenu = WebContextMenu.show(
            false,
            browserFragment.requireActivity(),
            BrowserDownloadCallback(
                browserFragment,
                browserFragment.permissionHandler
            ),
            hitTarget
        )
    }

    override fun onEnterFullScreen(callback: TabView.FullscreenCallback, view: View?) {
        if (session == null) {
            return
        }
        val binding = browserFragment.binding ?: return
        if (!isForegroundSession(session)) {
            callback.fullScreenExited()
            return
        }
        browserFragment.fullscreenCallback = callback
        if (session?.engineSession?.tabView != null && view != null) {
            // Hide browser UI and web content
            binding.appBar.visibility = View.INVISIBLE
            binding.webviewContainer.visibility = View.INVISIBLE
            binding.browserBottomBar.visibility = View.INVISIBLE

            // Add view to video container and make it visible
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            binding.videoContainer.addView(view, params)
            binding.videoContainer.visibility = View.VISIBLE

            browserFragment.hidePluggableUi()

            // Switch to immersive mode: Hide system bars other UI controls
            browserFragment.systemVisibility =
                ViewUtils.switchToImmersiveMode(browserFragment.activity)
        }
    }

    override fun onExitFullScreen() {
        if (session == null) {
            return
        }
        val binding = browserFragment.binding ?: return
        // Remove custom video views and hide container
        binding.videoContainer.removeAllViews()
        binding.videoContainer.visibility = View.GONE

        // Show browser UI and web content again
        binding.appBar.visibility = View.VISIBLE
        binding.webviewContainer.visibility = View.VISIBLE
        binding.browserBottomBar.visibility = View.VISIBLE
        if (browserFragment.systemVisibility != ViewUtils.SYSTEM_UI_VISIBILITY_NONE) {
            ViewUtils.exitImmersiveMode(
                browserFragment.systemVisibility,
                browserFragment.activity
            )
        }
        browserFragment.showPluggableUi()

        // Notify renderer that we left fullscreen mode.
        browserFragment.fullscreenCallback?.let {
            it.fullScreenExited()
            browserFragment.fullscreenCallback = null
        }

        // WebView gets focus, but unable to open the keyboard after exit Fullscreen for Android 7.0+
        // We guess some component in WebView might lock focus
        // So when user touches the input text box on Webview, it will not trigger to open the keyboard
        // It may be a WebView bug.
        // The workaround is clearing WebView focus
        // The WebView will be normal when it gets focus again.
        // If android change behavior after, can remove this.
        session?.engineSession?.tabView?.let {
            if (it is WebView) {
                it.clearFocus()
            }
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback?
    ) {
        if (session == null) {
            return
        }
        if (!isForegroundSession(session) || !browserFragment.isPopupWindowAllowed) {
            return
        }
        browserFragment.showGeolocationPermission(origin, callback)
    }

    fun changeSession(nextSession: Session?) {
        session?.unregister(this)
        session = nextSession?.also {
            it.register(this)
        }
    }

    private fun updateUrlFromWebView(source: Session) {
        if (browserFragment.sessionManager.focusSession != null) {
            val viewURL = browserFragment.sessionManager.focusSession?.url
            onUrlChanged(source, viewURL)
        }
    }

    private fun isForegroundSession(tab: Session?): Boolean {
        return browserFragment.sessionManager.focusSession == tab
    }

    override fun onFindResult(
        session: Session,
        result: mozilla.components.browser.session.Session.FindResult
    ) {
        browserFragment.findInPage.onFindResultReceived(result)
    }

    override fun onDownload(
        session: Session,
        download: mozilla.components.browser.session.Download
    ): Boolean {
        val activity = browserFragment.activity
        if (activity == null || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return false
        }
        val d = Download(
            download.url,
            download.fileName,
            download.userAgent,
            "",
            download.contentType,
            requireNotNull(download.contentLength),
            false
        )
        browserFragment.permissionHandler.tryAction(
            browserFragment,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            BrowserFragment.ACTION_DOWNLOAD,
            d
        )
        return true
    }

    override fun onNavigationStateChanged(
        session: Session,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        browserFragment.chromeViewModel.onNavigationStateChanged(canGoBack, canGoForward)
    }

    override fun onHttpAuthRequest(
        callback: TabViewClient.HttpAuthCallback,
        host: String?,
        realm: String?
    ) {
        // TODO: too complicated. should be refactor
        val innerBuilder =
            HttpAuthenticationDialogBuilder.Builder(browserFragment.activity, host, realm)
        innerBuilder.setOkListener { _: String?, _: String?, username: String?, password: String? ->
            callback.proceed(username, password)
        }
        innerBuilder.setCancelListener { callback.cancel() }
        val builder = innerBuilder.build()
        builder.createDialog()
        builder.show()
    }
}
