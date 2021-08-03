/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.browser

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.drawable.TransitionDrawable
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
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import dagger.Lazy
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentBrowserBinding
import org.mozilla.focus.locale.LocaleAwareFragment
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.navigation.ScreenNavigator.BrowserScreen
import org.mozilla.focus.tabs.tabtray.TabTray
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.utils.SupportUtils
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.widget.BackKeyHandleable
import org.mozilla.focus.widget.FindInPage
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.BottomBarItemAdapter.Theme
import org.mozilla.rocket.chrome.BottomBarViewModel
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.ChromeViewModel.ScreenCaptureTelemetryData
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.content.view.BottomBar.BottomBarBehavior.Companion.slideUp
import org.mozilla.rocket.download.DownloadIndicatorIntroViewHelper.OnViewInflated
import org.mozilla.rocket.download.DownloadIndicatorIntroViewHelper.initDownloadIndicatorIntroView
import org.mozilla.rocket.download.DownloadIndicatorViewModel
import org.mozilla.rocket.download.DownloadIndicatorViewModel.Status
import org.mozilla.rocket.extension.switchFrom
import org.mozilla.rocket.extension.thenRun
import org.mozilla.rocket.shopping.search.ShoppingSearchController
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.TabView.FullscreenCallback
import org.mozilla.rocket.tabs.TabsSessionProvider
import org.mozilla.rocket.tabs.utils.TabUtil
import org.mozilla.rocket.tabs.web.Download
import org.mozilla.threadutils.ThreadUtils
import org.mozilla.urlutils.UrlUtils
import javax.inject.Inject
import org.mozilla.focus.telemetry.TelemetryWrapper.Extra_Value.WEBVIEW as EXTRA_WEB_VIEW

/**
 * Fragment for displaying the browser UI.
 */
class BrowserFragment : LocaleAwareFragment(), BrowserScreen, BackKeyHandleable {

    @Inject
    lateinit var downloadIndicatorViewModelCreator: Lazy<DownloadIndicatorViewModel>

    @Inject
    lateinit var bottomBarViewModelCreator: Lazy<BottomBarViewModel>

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    lateinit var chromeViewModel: ChromeViewModel
    lateinit var bottomBarViewModel: BottomBarViewModel
    private lateinit var bottomBarItemAdapter: BottomBarItemAdapter

    var binding: FragmentBrowserBinding? = null

    var systemVisibility = ViewUtils.SYSTEM_UI_VISIBILITY_NONE
    var isLoading = false

    lateinit var sessionManager: SessionManager
    private val sessionObserver = SessionObserver(this)
    private val managerObserver = SessionManagerObserver(this, sessionObserver)

    lateinit var findInPage: FindInPage

    lateinit var appBarBgTransition: TransitionDrawable
    lateinit var statusBarBgTransition: TransitionDrawable

    var webContextMenu: Dialog? = null

    var loadedUrl: String? = null

    var fullscreenCallback: FullscreenCallback? = null

    private var downloadIndicatorIntro: View? = null
    private var landscapeStartTime = 0L

    private val geolocationController = GeolocationPermissionController(this)
    private val captureCtrl = CaptureController(this)
    private val shoppingSearchCtrl = ShoppingSearchController(this)
    private val fileChooseController = FileChooseController(this)
    private val downloadCtrl = DownloadController(this)

    // getUrl() is used for things like sharing the current URL. We could try to use the webview,
    // but sometimes it's null, and sometimes it returns a null URL. Sometimes it returns a data:
    // URL for error pages. The URL we show in the toolbar is (A) always correct and (B) what the
    // user is probably expecting to share, so lets use that here:
    val url: String
        get() = binding?.toolbar?.displayUrl?.text?.toString().orEmpty()

    val isPopupWindowAllowed: Boolean
        get() = ScreenNavigator[context].isBrowserInForeground &&
            isAdded && !TabTray.isShowing(parentFragmentManager)

    override fun onCreate(savedInstanceState: Bundle?) {
        this.appComponent().inject(this)
        super.onCreate(savedInstanceState)
        bottomBarViewModel = getActivityViewModel(bottomBarViewModelCreator)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        lifecycle.addObserver(captureCtrl)
        lifecycle.addObserver(geolocationController)
        lifecycle.addObserver(shoppingSearchCtrl)
        lifecycle.addObserver(fileChooseController)
        lifecycle.addObserver(downloadCtrl)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentBrowserBinding.inflate(inflater, container, false).also {
        this.binding = it
    }.root

    override fun onResume() {
        sessionManager.resume()
        super.onResume()
    }

    override fun onPause() {
        sessionManager.pause()
        super.onPause()
    }

    override fun applyLocale() {
        // We create and destroy a new WebView here to force the internal state of WebView to know
        // about the new language. See issue #666.
        val unneeded = WebView(context)
        unneeded.destroy()
    }

    fun updateURL(url: String?) {
        if (UrlUtils.isInternalErrorURL(url)) {
            return
        }
        binding?.toolbar?.displayUrl?.text = UrlUtils.stripUserInfo(url)
        shoppingSearchCtrl.notifyUrlChanged()
    }

    private fun setupBottomBar() {
        val browserBottomBar = binding?.browserBottomBar ?: return
        bottomBarItemAdapter = BottomBarItemAdapter(browserBottomBar, Theme.Light)

        browserBottomBar.setOnItemClickListener { type, position ->
            updateChromeViewModelForBottomBarClick(type, position)
            sendTelemetryForBottomBarClick(type, position)
        }

        browserBottomBar.setOnItemLongClickListener { type: Int, _: Int ->
            if (type == BottomBarItemAdapter.TYPE_MENU) {
                // Long press menu always show download panel
                chromeViewModel.showDownloadPanel.call()
                TelemetryWrapper.longPressDownloadIndicator()
                true
            } else {
                false
            }
        }

        // if items in ViewModel changed, update adapter
        bottomBarViewModel.items.observeOnViewLifecycle { items ->
            bottomBarItemAdapter.setItems(items)
        }

        // This equals to of using switchMap. LiveData of tabCount will be RECREATED
        // via calling `map`, once `bottomBarViewModel.items` is updated.
        // bottomBarViewModel.items.switchMap { chromeViewModel.tabCount.map { it } }
        //     .observeOnViewLifecycle { count: Int -> ... }
        // Namely, `setTabCount` will be called whenever bottomBarViewModel.items are changed
        // regardless chromeViewModel.tabCount.value is changed or not.
        chromeViewModel.tabCount.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { count: Int ->
                bottomBarItemAdapter.setTabCount(count, true)
            }
        chromeViewModel.isDarkTheme.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { isDarkTheme ->
                bottomBarItemAdapter.setDarkTheme(isDarkTheme)
            }
        chromeViewModel.isRefreshing.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { isRefreshing: Boolean ->
                bottomBarItemAdapter.setRefreshing(isRefreshing)
            }
        chromeViewModel.canGoForward.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { canGoForward: Boolean ->
                bottomBarItemAdapter.setCanGoForward(canGoForward)
            }
        chromeViewModel.isCurrentUrlBookmarked.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { isBookmark: Boolean ->
                bottomBarItemAdapter.setBookmark(isBookmark)
            }
        setupDownloadIndicator()
    }

    private fun setupDownloadIndicator() {
        val downloadIndicatorViewModel = getActivityViewModel(downloadIndicatorViewModelCreator)
        downloadIndicatorViewModel
            .downloadIndicatorObservable
            .switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { status: Status ->
                val downloadState = when (status) {
                    Status.DOWNLOADING -> BottomBarItemAdapter.DOWNLOAD_STATE_DOWNLOADING
                    Status.UNREAD -> BottomBarItemAdapter.DOWNLOAD_STATE_UNREAD
                    Status.WARNING -> BottomBarItemAdapter.DOWNLOAD_STATE_WARNING
                    Status.DEFAULT -> BottomBarItemAdapter.DOWNLOAD_STATE_DEFAULT
                }
                bottomBarItemAdapter.setDownloadState(downloadState)

                val eventHistory = Settings.getInstance(activity).eventHistory
                if (!eventHistory.contains(Settings.Event.ShowDownloadIndicatorIntro) && status !== Status.DEFAULT) {
                    eventHistory.add(Settings.Event.ShowDownloadIndicatorIntro)
                    val menuItem = bottomBarItemAdapter.getItem(BottomBarItemAdapter.TYPE_MENU)
                    val rootView = binding?.root
                    if (rootView != null && menuItem?.view != null) {
                        initDownloadIndicatorIntroView(
                            this,
                            menuItem.view,
                            rootView,
                            object : OnViewInflated {
                                override fun onInflated(view: View) {
                                    downloadIndicatorIntro = view
                                }
                            }
                        )
                    }
                }
            }
    }

    override fun onViewCreated(container: View, savedInstanceState: Bundle?) {
        super.onViewCreated(container, savedInstanceState)
        val binding = this.binding ?: return

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
        appBarBgTransition = binding.urlbar.background as TransitionDrawable
        statusBarBgTransition = binding.insetCover.background as TransitionDrawable
        observeChromeAction()
        setupBottomBar()
        findInPage = FindInPage(container)
        initialiseNormalBrowserUi()
        sessionManager = TabsSessionProvider.getOrThrow(activity)
        sessionManager.register(managerObserver, this, false)
        shoppingSearchCtrl.onViewCreated(binding.shoppingSearchStub)

        // maybe Fragment was destroyed
        maybeRestoreWebViewState(savedInstanceState)
    }

    private fun maybeRestoreWebViewState(savedInstanceState: Bundle?) {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBottomBarLayout()
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            bottomBarViewModel.onScreenRotatedToLandscape(true)
            onLandscapeModeStart()
        } else {
            bottomBarViewModel.onScreenRotatedToLandscape(false)
            onLandscapeModeFinish()
        }
        refreshVideoContainer()
    }

    private fun updateBottomBarLayout() {
        val browserBottomBar = binding?.browserBottomBar ?: return
        val bottomBarHeight: Int = resources.getDimensionPixelOffset(R.dimen.fixed_menu_height)
        browserBottomBar.layoutParams = browserBottomBar.layoutParams.apply {
            height = bottomBarHeight
        }
        browserBottomBar.onScreenRotated()
    }

    private fun observeChromeAction() {
        chromeViewModel.isTurboModeEnabled.observeOnViewLifecycle { setContentBlockingEnabled(it) }
        chromeViewModel.isBlockImageEnabled.observeOnViewLifecycle { setImageBlockingEnabled(it) }
        chromeViewModel.doScreenshot.observeOnViewLifecycle { startCapture(it) }
        chromeViewModel.isDarkTheme.observeOnViewLifecycle { setDarkThemeEnabled(it) }
        chromeViewModel.goNext.observeOnViewLifecycle { canGoForward().thenRun { goForward() } }
        chromeViewModel.goBack.observeOnViewLifecycle { canGoBack().thenRun { goBack() } }

        chromeViewModel.refreshOrStop.observeOnViewLifecycle {
            if (isLoading) {
                stop()
            } else {
                reload()
            }
        }

        chromeViewModel.isBlockJavaScriptEnabled.observeOnViewLifecycle {
            setJavaScriptBlockingEnabled(it)
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

    private fun updateChromeViewModelForBottomBarClick(type: Int, position: Int) = when (type) {
        BottomBarItemAdapter.TYPE_TAB_COUNTER -> chromeViewModel.showTabTray.call()
        BottomBarItemAdapter.TYPE_MENU -> chromeViewModel.showBrowserMenu.call()
        BottomBarItemAdapter.TYPE_HOME -> chromeViewModel.showNewTab.call()
        BottomBarItemAdapter.TYPE_SEARCH -> chromeViewModel.showUrlInput.value = url
        BottomBarItemAdapter.TYPE_PIN_SHORTCUT -> chromeViewModel.pinShortcut.call()
        BottomBarItemAdapter.TYPE_BOOKMARK -> chromeViewModel.toggleBookmark()
        BottomBarItemAdapter.TYPE_REFRESH -> chromeViewModel.refreshOrStop.call()
        BottomBarItemAdapter.TYPE_SHARE -> chromeViewModel.share.call()
        BottomBarItemAdapter.TYPE_NEXT -> chromeViewModel.goNext.call()
        BottomBarItemAdapter.TYPE_CAPTURE -> chromeViewModel.onDoScreenshot(
            ScreenCaptureTelemetryData(EXTRA_WEB_VIEW, position)
        )
        else -> throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
    }

    private fun isInLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    // Workaround for full-screen WebView issue that the video doesn't fit the viewport
    // after rotating the device from portrait to landscape and vice versa. It could reduce
    // the issue happened rate by changing the video view layout size to a slight smaller size
    // then add to the full screen size again when the device is rotated.
    private fun refreshVideoContainer() {
        val videoContainer = binding?.videoContainer ?: return
        if (videoContainer.visibility != View.VISIBLE) {
            return
        }

        val width = (videoContainer.width * 0.99).toInt()
        val height = (videoContainer.height * 0.99).toInt()
        // height, width interchanged
        val workaroundParams = FrameLayout.LayoutParams(height, width)
        updateVideoContainerWithLayoutParams(workaroundParams)

        videoContainer.post {
            if (videoContainer.visibility != View.VISIBLE) {
                return@post
            }
            val fullParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            updateVideoContainerWithLayoutParams(fullParams)
        }
    }

    private fun startCapture(params: Parcelable?) {
        val currentTab = sessionManager.focusSession ?: return
        val currentWebView = currentTab.engineSession?.tabView as? WebView ?: return
        captureCtrl.capture(currentWebView, params as? ScreenCaptureTelemetryData) {
            // My shot on boarding didn't show before and capture is succeed, skip to show toast
            checkToShowMyShotOnBoarding()
        }
    }

    private fun updateVideoContainerWithLayoutParams(params: FrameLayout.LayoutParams) {
        val videoContainer = binding?.videoContainer ?: return
        val fullscreenContentView = videoContainer.getChildAt(0) ?: return
        videoContainer.removeAllViews()
        videoContainer.addView(fullscreenContentView, params)
    }

    private fun onLandscapeModeStart() {
        landscapeStartTime = System.currentTimeMillis()
        TelemetryWrapper.enterLandscapeMode()
    }

    private fun onLandscapeModeFinish() {
        if (landscapeStartTime == 0L) {
            return
        }
        val duration = System.currentTimeMillis() - landscapeStartTime
        TelemetryWrapper.exitLandscapeMode(duration)
        landscapeStartTime = 0L
    }

    override fun goBackground() {
        val current = sessionManager.focusSession ?: return
        val es = current.engineSession ?: return
        es.detach()
        val tabView = es.tabView ?: return
        binding?.webviewSlot?.removeView(tabView.getView())
    }

    override fun goForeground() {
        val current = sessionManager.focusSession ?: return
        val tabView = current.engineSession?.tabView ?: return
        val webViewSlot = binding?.webviewSlot ?: return

        if (webViewSlot.childCount == 0) {
            webViewSlot.addView(tabView.getView())
        }
    }

    private fun initialiseNormalBrowserUi() {
        binding?.toolbar?.displayUrl?.setOnClickListener {
            chromeViewModel.showUrlInput.value = url
            // TODO: Needs to confirm with bi that what vertical should be passed into in normal browser using cases
            // TODO: For now just pass a empty string
            TelemetryWrapper.clickUrlbar("", isInLandscape())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessionManager.focusSession?.engineSession?.tabView?.saveViewState(outState)

        // Workaround for #1107 TransactionTooLargeException
        // since Android N, system throws a exception rather than just a warning(then drop bundle)
        // To set a threshold for dropping WebView state manually
        // refer: https://issuetracker.google.com/issues/37103380
        val key = "WEBVIEW_CHROMIUM_STATE"
        if (outState.containsKey(key)) {
            val size = outState.getByteArray(key)?.size ?: -1
            if (size > BUNDLE_MAX_SIZE) {
                outState.remove(key)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (systemVisibility != ViewUtils.SYSTEM_UI_VISIBILITY_NONE) {
            sessionManager.focusSession?.engineSession?.tabView?.performExitFullScreen()
        }
        geolocationController.dismissGeolocationDialog()
        super.onStop()
    }

    override fun onDestroyView() {
        sessionManager.unregister(managerObserver)
        shoppingSearchCtrl.onDestroyView()
        binding = null
        super.onDestroyView()
    }

    private fun setContentBlockingEnabled(enabled: Boolean) {
        // TODO: Better if we can move this logic to some setting-like classes, and provider interface
        // for configuring blocking function of each tab.
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.setContentBlockingEnabled(enabled)
        }
    }

    private fun setImageBlockingEnabled(enabled: Boolean) {
        // TODO: Better if we can move this logic to some setting-like classes, and provider interface
        // for configuring blocking function of each tab.
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.setImageBlockingEnabled(enabled)
        }
    }

    private fun setJavaScriptBlockingEnabled(enabled: Boolean) {
        // TODO: Better if we can move this logic to some setting-like classes, and provider interface
        // for configuring JavaScript blocking function of each tab.
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.setJavaScriptBlockingEnabled(enabled)
        }
    }

    fun updateIsLoading(isLoading: Boolean) {
        this.isLoading = isLoading
    }

    override fun onBackPressed(): Boolean {
        if (findInPage.onBackPressed()) {
            return true
        }

        // After we apply the full screen rotation workaround - 'refreshVideoContainer',
        // it may not be able to get 'onExitFullScreen' callback from WebChromeClient. Just call it here
        // to leave the full screen mode.
        if (binding?.videoContainer?.visibility == View.VISIBLE) {
            sessionObserver.onExitFullScreen()
            return true
        }
        if (canGoBack()) {
            // Go back in web history
            goBack()
        } else {
            val focus = sessionManager.focusSession ?: return false
            if (focus.isFromExternal || focus.hasParentTab()) {
                sessionManager.closeTab(focus.id)
            } else {
                ScreenNavigator[context].popToHomeScreen(true)
            }
        }
        return true
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
        loadedUrl = url
        if (SupportUtils.isUrl(url)) {
            if (openNewTab) {
                sessionManager.addTab(url, TabUtil.argument(null, isFromExternal, true))
                // Per spec, if download indicator intro view is showed when new tabb is opened, just dismiss it anyway.
                dismissDownloadIndicatorIntroView()
                // In case we call SessionManager#addTab(), which is an async operation calls back in the next
                // message loop. By posting this runnable we can call back in the same message loop with
                // TabsContentListener#onFocusChanged(), which is when the view is ready and being attached.
                ThreadUtils.postToMainThread(onViewReadyCallback)
            } else {
                val currentTab = sessionManager.focusSession
                if (currentTab?.engineSession?.tabView != null) {
                    currentTab.engineSession?.tabView?.loadUrl(url)
                    onViewReadyCallback?.run()
                } else {
                    sessionManager.addTab(url, TabUtil.argument(null, isFromExternal, true))
                    ThreadUtils.postToMainThread(onViewReadyCallback)
                }
            }
        } else if (AppConstants.isDevBuild()) {
            // throw exception to highlight this issue, except release build.
            throw RuntimeException("trying to open a invalid url: $url")
        }
    }

    override fun switchToTab(tabId: String) {
        if (!TextUtils.isEmpty(tabId)) {
            sessionManager.switchToTab(tabId)
        }
    }

    override fun getFragment(): Fragment {
        return this
    }

    fun canGoForward(): Boolean = sessionManager.focusSession?.canGoForward == true

    fun canGoBack(): Boolean = sessionManager.focusSession?.canGoBack == true

    private fun goBack() {
        val currentTab = sessionManager.focusSession
        if (currentTab != null) {
            val current = currentTab.engineSession?.tabView
            // The Session.canGoBack property is mainly for UI display purpose and is only sampled
            // at onNavigationStateChange which is called at onPageFinished, onPageStarted and
            // onReceivedTitle. We do some sanity check here.
            if (current == null || !current.canGoBack()) {
                return
            }
            current.goBack()
            if ((current as WebView).originalUrl != null) {
                loadedUrl = (current as WebView).originalUrl
            }
        }
    }

    private fun goForward() {
        val currentTab = sessionManager.focusSession
        if (currentTab != null) {
            val current = currentTab.engineSession?.tabView ?: return
            current.goForward()
            if ((current as WebView).originalUrl != null) {
                loadedUrl = (current as WebView).originalUrl
            }
        }
    }

    private fun reload() {
        sessionManager.focusSession?.engineSession?.tabView?.reload()
    }

    private fun stop() {
        sessionManager.focusSession?.engineSession?.tabView?.stopLoading()
    }

    fun dismissAllMenus() {
        dismissWebContextMenu()
        geolocationController.dismissGeolocationDialog()
    }

    private fun dismissWebContextMenu() {
        webContextMenu?.let {
            it.dismiss()
            webContextMenu = null
        }
    }

    fun getWebViewSlot(): ViewGroup? = binding?.webviewSlot

    private fun showFindInPage() {
        val binding = this.binding ?: return
        val focusTab = sessionManager.focusSession ?: return

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

    fun hideFindInPage() {
        findInPage.hide()
    }

    fun showPluggableUi() {
        shoppingSearchCtrl.setVisible()
    }

    fun hidePluggableUi() {
        shoppingSearchCtrl.setInvisible()
    }

    fun showGeolocationPermission(origin: String, callback: GeolocationPermissions.Callback?) {
        geolocationController.showGeolocationDialog(origin, callback)
    }

    fun closeGeolocationPermission() {
        geolocationController.dismissGeolocationDialog()
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
        chromeViewModel.onEnqueueDownload(download, url)
    }

    private fun checkToShowMyShotOnBoarding() {
        chromeViewModel.checkToShowMyShotOnBoarding()
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
        ViewUtils.updateStatusBarStyle(!enable, requireActivity().window)
    }

    private fun dismissDownloadIndicatorIntroView() {
        downloadIndicatorIntro?.visibility = View.GONE
    }

    private fun sendTelemetryForBottomBarClick(type: Int, position: Int) = when (type) {
        BottomBarItemAdapter.TYPE_TAB_COUNTER ->
            TelemetryWrapper.showTabTrayToolbar(EXTRA_WEB_VIEW, position, isInLandscape())
        BottomBarItemAdapter.TYPE_MENU ->
            TelemetryWrapper.showMenuToolbar(EXTRA_WEB_VIEW, position)
        BottomBarItemAdapter.TYPE_HOME ->
            TelemetryWrapper.clickAddTabToolbar(EXTRA_WEB_VIEW, position, isInLandscape())
        BottomBarItemAdapter.TYPE_SEARCH ->
            TelemetryWrapper.clickToolbarSearch(EXTRA_WEB_VIEW, position, isInLandscape())
        BottomBarItemAdapter.TYPE_PIN_SHORTCUT ->
            TelemetryWrapper.clickAddToHome(EXTRA_WEB_VIEW, position)
        BottomBarItemAdapter.TYPE_REFRESH ->
            TelemetryWrapper.clickToolbarReload(EXTRA_WEB_VIEW, position, isInLandscape())
        BottomBarItemAdapter.TYPE_SHARE ->
            TelemetryWrapper.clickToolbarShare(EXTRA_WEB_VIEW, position, isInLandscape())
        BottomBarItemAdapter.TYPE_NEXT ->
            TelemetryWrapper.clickToolbarForward(EXTRA_WEB_VIEW, position)
        BottomBarItemAdapter.TYPE_BOOKMARK -> {
            val bookmarkItem = bottomBarItemAdapter.getItem(BottomBarItemAdapter.TYPE_BOOKMARK)
            val isActivated = bookmarkItem?.view?.isActivated == true
            TelemetryWrapper.clickToolbarBookmark(isActivated, EXTRA_WEB_VIEW, position)
        }
        BottomBarItemAdapter.TYPE_CAPTURE -> Unit
        else -> throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
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

    companion object {
        /**
         * Custom data that is passed when calling [SessionManager.addTab]
         */
        const val EXTRA_NEW_TAB_SRC = "extra_bkg_tab_src"
        const val SRC_CONTEXT_MENU = 0

        const val ANIMATION_DURATION = 300
        const val SITE_GLOBE = 0
        const val SITE_LOCK = 1
        const val BUNDLE_MAX_SIZE = 300 * 1000 // 300K
    }
}
