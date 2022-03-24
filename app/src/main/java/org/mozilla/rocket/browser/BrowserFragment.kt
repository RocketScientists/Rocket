/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.browser

import android.app.Dialog
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowInsets
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.android.material.snackbar.Snackbar
import dagger.Lazy
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentBrowserBinding
import org.mozilla.focus.locale.LocaleAwareFragment
import org.mozilla.focus.menu.WebContextMenu
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.navigation.ScreenNavigator.BrowserScreen
import org.mozilla.focus.screenshot.CaptureRunnable
import org.mozilla.focus.tabs.tabtray.TabTray
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.SupportUtils
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.widget.FindInPage
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.ChromeViewModel.ScreenCaptureTelemetryData
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.content.view.BottomBar.BottomBarBehavior.Companion.slideUp
import org.mozilla.rocket.extension.UrlStringExtension.removeUrlFragment
import org.mozilla.rocket.shopping.search.ShoppingSearchController
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.TabView
import org.mozilla.rocket.tabs.TabView.FullscreenCallback
import org.mozilla.rocket.tabs.web.Download
import org.mozilla.rocket.tabs.web.DownloadCallback
import org.mozilla.urlutils.UrlUtils
import java.lang.ref.WeakReference
import javax.inject.Inject
import mozilla.components.browser.session.Session.FindResult as MozillaFindResult

/**
 * Fragment for displaying the browser UI.
 */
class BrowserFragment : LocaleAwareFragment(), BrowserScreen {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    lateinit var chromeViewModel: ChromeViewModel

    var binding: FragmentBrowserBinding? = null

    private var isLoading = false

    private lateinit var findInPage: FindInPage

    var loadedUrl: String? = null

    private var fullscreenCallback: FullscreenCallback? = null

    @set:VisibleForTesting
    var captureStateListener: CaptureRunnable.CaptureStateListener? = null

    private var webContextMenu: WeakReference<Dialog>? = null
    private var landscapeStartTime = 0L

    private val sessionCtrl = SessionController(this)
    private val viewController = BrowserFragmentViewController(this)
    private val bottomBarCtrl = BottomBarController(this)

    private val geolocationController = GeolocationPermissionController(this)
    private val captureCtrl = CaptureController(this)
    private val fileChooseController = FileChooseController(this)
    private val downloadCtrl = DownloadController(this)

    private var shoppingSearchCtrl: ShoppingSearchController? = null

    // This is used for things like sharing the current Url. We could try to access Url of WebView,
    // but sometimes itself is null, and sometimes it returns a null Url. Sometimes it returns a
    // Url with `data:` scheme for error pages. The Url we show in the toolbar should be 1) always
    // correct and 2) a Url that user is probably expecting to share, so lets use that here:
    val chromeUrl: String
        get() = binding?.toolbar?.displayUrl?.text?.toString().orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        this.appComponent().inject(this)
        super.onCreate(savedInstanceState)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        lifecycle.addObserver(sessionCtrl)
        lifecycle.addObserver(captureCtrl)
        lifecycle.addObserver(geolocationController)
        lifecycle.addObserver(fileChooseController)
        lifecycle.addObserver(downloadCtrl)

        if (chromeViewModel.isInPrivateMode) {
            shoppingSearchCtrl = ShoppingSearchController(this).also { lifecycle.addObserver(it) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentBrowserBinding.inflate(inflater, container, false).also {
        this.binding = it
    }.root

    override fun applyLocale() {
        // We create and destroy a new WebView here to force the internal state of WebView to know
        // about the new language. See issue #666.
        context?.let {
            val unneeded = WebView(it)
            unneeded.destroy()
        }
    }

    fun updateChromeUrl(url: String?) {
        if (UrlUtils.isInternalErrorURL(url)) {
            return
        }
        binding?.toolbar?.displayUrl?.text = UrlUtils.stripUserInfo(url)
        shoppingSearchCtrl?.notifyUrlChanged()
    }

    fun updateLoadingState(isLoading: Boolean) {
        this.isLoading = isLoading
        viewController.updateLoadingState(isLoading)

        if (isLoading) {
            loadedUrl = null
        }
    }

    override fun onViewCreated(container: View, savedInstanceState: Bundle?) {
        super.onViewCreated(container, savedInstanceState)
        val binding = this.binding ?: return

        viewLifecycleOwner.lifecycle.addObserver(bottomBarCtrl)
        viewLifecycleOwner.lifecycle.addObserver(viewController)

        binding.appBar.setOnApplyWindowInsetsListener { v: View, insets: WindowInsets ->
            (v.layoutParams as MarginLayoutParams).topMargin = insets.systemWindowInsetTop
            // we might leak Views here
            binding.insetCover.layoutParams?.height = insets.systemWindowInsetTop
            insets
        }
        binding.mainContent.setOnApplyWindowInsetsListener { v: View, insets: WindowInsets ->
            v.setPadding(0, 0, 0, insets.systemWindowInsetTop)
            insets
        }

        observeChromeAction()
        findInPage = FindInPage(container)
        initialiseNormalBrowserUi()
        shoppingSearchCtrl?.onViewCreated(binding.shoppingSearchStub)

        // maybe Fragment was destroyed
        sessionCtrl.maybeRestoreWebViewState(savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        bottomBarCtrl.updateForScreenRotation(isLandscape)
        viewController.refreshVideoContainer()
        recordLandscapeModeTime(isLandscape)
    }

    private fun observeChromeAction() {
        chromeViewModel.isPrivateTurboModeEnabled.observeOnViewLifecycle {
            if (chromeViewModel.isInPrivateMode) {
                sessionCtrl.setContentBlockingEnabled(it)
                sessionCtrl.stopLoadingTabs()
                sessionCtrl.reloadingTabs()
            }
        }
        chromeViewModel.isTurboModeEnabled.observeOnViewLifecycle {
            sessionCtrl.setContentBlockingEnabled(it)
        }
        chromeViewModel.isBlockImageEnabled.observeOnViewLifecycle {
            sessionCtrl.setImageBlockingEnabled(it)
        }
        chromeViewModel.isCurrentSessionSecure.observeOnViewLifecycle { updateSiteIdentity(it) }
        chromeViewModel.doScreenshot.observeOnViewLifecycle { startCapture(it) }
        chromeViewModel.isDarkTheme.observeOnViewLifecycle { setDarkThemeEnabled(it) }
        chromeViewModel.goNext.observeOnViewLifecycle { sessionCtrl.maybeGoForward() }
        chromeViewModel.goBack.observeOnViewLifecycle { sessionCtrl.maybeGoBack() }

        chromeViewModel.refreshOrStop.observeOnViewLifecycle {
            if (isLoading) {
                sessionCtrl.stop()
            } else {
                sessionCtrl.reload()
            }
        }

        chromeViewModel.isBlockJavaScriptEnabled.observeOnViewLifecycle {
            sessionCtrl.setJavaScriptBlockingEnabled(it)
        }

        chromeViewModel.showFindInPage.observeOnViewLifecycle {
            if (chromeViewModel.navigationState.value?.isBrowser == true) {
                showFindInPage()
            }
        }

        chromeViewModel.currentUrl.observeOnViewLifecycle {
            binding?.appBar?.setExpanded(true)
            binding?.browserBottomBar?.slideUp()
        }
    }

    private fun isInLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun startCapture(params: Parcelable?) {
        val currentTab = sessionCtrl.getFocusSession() ?: return
        val currentWebView = currentTab.engineSession?.tabView as? WebView ?: return
        captureCtrl.capture(currentWebView, params as? ScreenCaptureTelemetryData) {
            // My shot on boarding didn't show before and capture is succeed, skip to show toast
            chromeViewModel.checkToShowMyShotOnBoarding()
        }
    }

    private fun recordLandscapeModeTime(isLandscape: Boolean) {
        if (chromeViewModel.isInPrivateMode) {
            return
        }

        if (isLandscape) {
            landscapeStartTime = System.currentTimeMillis()
            TelemetryWrapper.enterLandscapeMode()
        } else {
            if (landscapeStartTime == 0L) {
                return
            }
            val duration = System.currentTimeMillis() - landscapeStartTime
            TelemetryWrapper.exitLandscapeMode(duration)
            landscapeStartTime = 0L
        }
    }

    override fun goBackground() {
        val current = sessionCtrl.getFocusSession() ?: return
        val es = current.engineSession ?: return
        es.detach()
        val tabView = es.tabView ?: return
        binding?.webviewSlot?.removeView(tabView.getView())
    }

    override fun goForeground() {
        val current = sessionCtrl.getFocusSession() ?: return
        val tabView = current.engineSession?.tabView ?: return
        val webViewSlot = binding?.webviewSlot ?: return

        if (webViewSlot.childCount == 0) {
            webViewSlot.addView(tabView.getView())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessionCtrl.saveViewStateFromFocusedSession(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        geolocationController.dismissGeolocationDialog()
        super.onStop()
    }

    override fun onDestroyView() {
        shoppingSearchCtrl?.onDestroyView()
        binding = null
        super.onDestroyView()
    }

    override fun onBackPressed(): Boolean {
        if (findInPage.onBackPressed()) {
            return true
        }

        // After we apply the full screen rotation workaround - 'refreshVideoContainer',
        // it may not be able to get 'onExitFullScreen' callback from WebChromeClient. Just call it here
        // to leave the full screen mode.
        if (viewController.isInVideoFullScreen()) {
            sessionCtrl.chromeExitFullScreen()
            return true
        }
        return sessionCtrl.handleBackKey()
    }

    /**
     * @param url target url
     * @param openNewTab whether to load url in a new tab or not
     * @param isFromExternal if this url is started from external VIEW intent
     * @param onViewReadyCallback callback to notify that web view is ready for showing.
     */
    override fun loadUrl(
        url: String,
        openNewTab: Boolean,
        isFromExternal: Boolean,
        onViewReadyCallback: Runnable?
    ) {
        if (!SupportUtils.isUrl(url)) {
            if (AppConstants.isDevBuild()) {
                // throw exception to highlight this issue, except release build.
                throw RuntimeException("trying to open an invalid url: $url")
            }

            return
        }

        if (openNewTab) {
            // Per spec, if download indicator intro view is showed when new tabb is opened
            // just dismiss it anyway.
            bottomBarCtrl.dismissDownloadIndicatorIntroView()
        }

        loadedUrl = url
        sessionCtrl.loadUrl(url, openNewTab, isFromExternal, onViewReadyCallback)
    }

    override fun switchToTab(tabId: String) {
        sessionCtrl.switchToTab(tabId)
    }

    override fun getFragment(): Fragment {
        return this
    }

    fun refreshChrome(focusSession: Session) {
        geolocationController.dismissGeolocationDialog()
        updateChromeUrl(focusSession.url)

        if (focusSession.progress == 0 || focusSession.progress == 100) {
            binding?.progressBar?.visibility = View.GONE
        } else {
            binding?.progressBar?.progress = focusSession.progress
        }

        updateSiteIdentity(focusSession.securityInfo.secure)
        hideFindInPage()
        // check if newer config exists whenever navigating to Browser screen
        bottomBarCtrl.refreshViewModel()
    }

    fun dismissAllMenus() {
        dismissWebContextMenu()
        geolocationController.dismissGeolocationDialog()
    }

    fun maybeShowGeolocationPermission(origin: String, callback: GeolocationPermissions.Callback?) {
        val isPopupWindowAllowed = isAdded &&
            ScreenNavigator[context].isBrowserInForeground &&
            !TabTray.isShowing(parentFragmentManager)

        if (!isPopupWindowAllowed) {
            return
        }
        geolocationController.showGeolocationDialog(origin, callback)
    }

    fun chooseFile(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams
    ) {
        fileChooseController.maybeChooseFile(callback, params)
    }

    fun maybeQueueDownload(download: Download) {
        downloadCtrl.maybeQueueDownload(download)
    }

    /**
     * Use Android's Download Manager to queue this download.
     */
    fun addToDownloadManager(download: Download?) {
        if (activity == null || download == null) {
            return
        }
        val shouldBeRecorded = !chromeViewModel.isInPrivateMode
        chromeViewModel.onEnqueueDownload(download, chromeUrl, shouldBeRecorded)
    }

    fun enterFullScreen(callback: FullscreenCallback, view: View) {
        fullscreenCallback = callback
        viewController.enterVideoFullScreen(view)
        hidePluggableUi()
    }

    fun exitFullScreen() {
        viewController.exitVideoFullScreen()
        showPluggableUi()

        // Notify renderer that we left fullscreen mode.
        fullscreenCallback?.fullScreenExited()
        fullscreenCallback = null
    }

    fun showSnackBarForAddedSession(clickAction: View.OnClickListener) {
        val binding = binding ?: return
        val snackBar = Snackbar.make(
            binding.root, R.string.new_background_tab_hint,
            Snackbar.LENGTH_LONG
        )
        snackBar.setAction(R.string.new_background_tab_switch, clickAction)
        snackBar.show()
    }

    private fun updateSiteIdentity(isSecure: Boolean) {
        val level = if (isSecure) SITE_LOCK else SITE_GLOBE
        binding?.toolbar?.siteIdentity?.setImageLevel(level)
    }

    fun updateProgressOfSession(session: Session, progress: Int) {
        hideFindInPage()
        // Remove URL fragment to prevent progress bar update when location.hash change
        // (follow Chrome and Firefox for Android behavior)
        val baseSessionUrl = session.url?.removeUrlFragment()
        val baseLoadedUrl = loadedUrl?.removeUrlFragment()
        val progressIsForLoadedUrl = TextUtils.equals(baseSessionUrl, baseLoadedUrl)

        // Some new url may give 100 directly and then start from 0 again. don't treat
        // as loaded for these urls;
        val progressBar = binding?.progressBar
        val sessionIsFinishingLoading = if (progressBar == null) {
            false
        } else {
            val progressBarWasNotFull = progressBar.max != progressBar.progress
            val progressBarIsNowFull = progress == progressBar.max
            progressBarWasNotFull && progressBarIsNowFull
        }
        if (sessionIsFinishingLoading) {
            loadedUrl = session.url
        }
        // Some URL cause progress bar to stuck at loading state,
        // allowing progress update to progressBar.max solve the issue
        if (progressIsForLoadedUrl && progress != progressBar?.max) {
            return
        }
        progressBar?.progress = progress
    }

    fun showContextMenu(hitTarget: TabView.HitTarget) {
        val activity = activity ?: return
        val dialog = WebContextMenu.show(
            false,
            activity,
            BrowserDownloadCallback(this),
            hitTarget
        )
        webContextMenu = WeakReference(dialog)
    }

    fun getSnackBarAnchor(): View? {
        return binding?.browserBottomBar
    }

    fun isSystemUiChanged(): Boolean {
        return viewController.isSystemUiChanged()
    }

    fun setReceivedFindResult(result: MozillaFindResult) {
        findInPage.onFindResultReceived(result)
    }

    fun transitToTab(inView: View?) {
        viewController.transitToTab(inView)
    }

    private fun initialiseNormalBrowserUi() {
        binding?.toolbar?.displayUrl?.setOnClickListener {
            chromeViewModel.showUrlInput.value = chromeUrl
            // TODO: Needs to confirm with bi that what vertical should be passed into in normal browser using cases
            // TODO: For now just pass a empty string
            TelemetryWrapper.clickUrlbar("", isInLandscape())
        }
    }

    private fun dismissWebContextMenu() {
        webContextMenu?.get()?.dismiss()
        webContextMenu = null
    }

    private fun showPluggableUi() {
        shoppingSearchCtrl?.setVisible()
    }

    private fun hidePluggableUi() {
        shoppingSearchCtrl?.setInvisible()
    }

    private fun setDarkThemeEnabled(enable: Boolean) {
        val binding = binding ?: return
        binding.root.setDarkTheme(enable)
        binding.browserBottomBar.setDarkTheme(enable)
        binding.insetCover.setDarkTheme(enable)
        binding.toolbar.toolbarRoot.setDarkTheme(enable)
        binding.toolbar.displayUrl.setDarkTheme(enable)
        binding.toolbar.siteIdentity.setDarkTheme(enable)
        binding.urlbar.setDarkTheme(enable)
        binding.urlBarDivider.setDarkTheme(enable)
        val isLightStatusBarIcon = !enable && !chromeViewModel.isInPrivateMode
        ViewUtils.updateStatusBarStyle(isLightStatusBarIcon, requireActivity().window)
    }

    private fun showFindInPage() {
        val binding = this.binding ?: return
        val focusTab = sessionCtrl.getFocusSession() ?: return

        binding.root.isActivated = false
        binding.appBar.setExpanded(false)
        binding.browserBottomBar.visibility = View.INVISIBLE
        hidePluggableUi()
        findInPage.onDismissListener = {
            binding.root.isActivated = true
            binding.appBar.setExpanded(true)
            binding.browserBottomBar.visibility = View.VISIBLE
            showPluggableUi()
        }
        findInPage.show(focusTab)
        TelemetryWrapper.findInPage(TelemetryWrapper.FIND_IN_PAGE.OPEN_BY_MENU)
    }

    private fun hideFindInPage() {
        findInPage.hide()
    }

    /**
     * A helper function to observer a LiveData via View's lifecycle
     */
    private fun <X> LiveData<X>.observeOnViewLifecycle(
        lifecycleOwner: LifecycleOwner = viewLifecycleOwner,
        observer: Observer<X>
    ) {
        this.observe(lifecycleOwner, observer)
    }

    private class BrowserDownloadCallback(
        private val fragment: BrowserFragment
    ) : DownloadCallback {
        override fun onDownloadStart(download: Download) {
            fragment.maybeQueueDownload(download)
        }
    }

    companion object {
        /**
         * Custom data that is passed when calling [SessionManager.addTab]
         */
        const val EXTRA_NEW_TAB_SRC = "extra_bkg_tab_src"
        const val SRC_CONTEXT_MENU = 0

        const val SITE_GLOBE = 0
        const val SITE_LOCK = 1
    }
}
