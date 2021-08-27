/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import com.google.android.material.snackbar.Snackbar
import dagger.Lazy
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentBrowserBinding
import org.mozilla.focus.locale.LocaleAwareFragment
import org.mozilla.focus.menu.WebContextMenu
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.navigation.ScreenNavigator.BrowserScreen
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

    private var systemVisibility = ViewUtils.SYSTEM_UI_VISIBILITY_NONE
    private var isLoading = false

    private lateinit var findInPage: FindInPage

    private lateinit var appBarBgTransition: TransitionDrawable
    private lateinit var statusBarBgTransition: TransitionDrawable

    var loadedUrl: String? = null

    private var fullscreenCallback: FullscreenCallback? = null

    private var webContextMenu: WeakReference<Dialog>? = null
    private var landscapeStartTime = 0L

    private val sessionCtrl = SessionController(this)
    private val bottomBarCtrl = BottomBarController(this)

    private val geolocationController = GeolocationPermissionController(this)
    private val captureCtrl = CaptureController(this)
    private val fileChooseController = FileChooseController(this)
    private val downloadCtrl = DownloadController(this)

    private var shoppingSearchCtrl: ShoppingSearchController? = null

    private var tabTransitionAnimator: ValueAnimator? = null

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
        val unneeded = WebView(context)
        unneeded.destroy()
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

        if (isLoading) {
            loadedUrl = null
            appBarBgTransition.resetTransition()
            statusBarBgTransition.resetTransition()
        } else {
            appBarBgTransition.startTransition(ANIMATION_DURATION)
            statusBarBgTransition.startTransition(ANIMATION_DURATION)
        }
    }

    override fun onViewCreated(container: View, savedInstanceState: Bundle?) {
        super.onViewCreated(container, savedInstanceState)
        val binding = this.binding ?: return

        viewLifecycleOwner.lifecycle.addObserver(bottomBarCtrl)

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
        appBarBgTransition = binding.toolbar.toolbarRoot.background as TransitionDrawable
        statusBarBgTransition = binding.insetCover.background as TransitionDrawable
        observeChromeAction()
        findInPage = FindInPage(container)
        initialiseNormalBrowserUi()
        shoppingSearchCtrl?.onViewCreated(binding.shoppingSearchStub)

        // maybe Fragment was destroyed
        sessionCtrl.maybeRestoreWebViewState(savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBottomBarLayout()
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            bottomBarCtrl.screenRotateToLandscape(true)
            onLandscapeModeStart()
        } else {
            bottomBarCtrl.screenRotateToLandscape(false)
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
        val currentTab = sessionCtrl.getFocusSession() ?: return
        val currentWebView = currentTab.engineSession?.tabView as? WebView ?: return
        captureCtrl.capture(currentWebView, params as? ScreenCaptureTelemetryData) {
            // My shot on boarding didn't show before and capture is succeed, skip to show toast
            chromeViewModel.checkToShowMyShotOnBoarding()
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
        if (binding?.videoContainer?.visibility == View.VISIBLE) {
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
        val binding = binding ?: return
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

        hidePluggableUi()

        // Switch to immersive mode: Hide system bars other UI controls
        systemVisibility = ViewUtils.switchToImmersiveMode(activity)
    }

    fun exitFullScreen() {
        val binding = binding ?: return
        // Remove custom video views and hide container
        binding.videoContainer.removeAllViews()
        binding.videoContainer.visibility = View.GONE

        // Show browser UI and web content again
        binding.appBar.visibility = View.VISIBLE
        binding.webviewContainer.visibility = View.VISIBLE
        binding.browserBottomBar.visibility = View.VISIBLE
        if (systemVisibility != ViewUtils.SYSTEM_UI_VISIBILITY_NONE) {
            // TODO: check, should we reset systemVisibility after exiting immersive mode?
            ViewUtils.exitImmersiveMode(systemVisibility, activity)
        }
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
        return systemVisibility != ViewUtils.SYSTEM_UI_VISIBILITY_NONE
    }

    fun setReceivedFindResult(result: MozillaFindResult) {
        findInPage.onFindResultReceived(result)
    }

    fun transitToTab(inView: View?) {
        val webViewSlot = binding?.webviewSlot ?: return

        val outView = webViewSlot.findExistingTabView()
        webViewSlot.removeView(outView)
        webViewSlot.addView(inView)

        if (inView != null) {
            startTransitionAnimation(null, inView)
        }
    }

    private fun startTransitionAnimation(outView: View?, inView: View) {
        stopTabTransition()
        inView.alpha = 0f
        outView?.alpha = 1f

        tabTransitionAnimator = createTransitionAnimator(inView, outView)
        tabTransitionAnimator?.start()
    }

    private fun createTransitionAnimator(inView: View, outView: View?): ValueAnimator {
        val duration = resources.getInteger(R.integer.tab_transition_time).toLong()
        val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(duration)
        animator.addUpdateListener { animation ->
            val alpha = animation.animatedValue as Float
            inView.alpha = alpha
            outView?.alpha = 1 - alpha
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                inView.alpha = 1f
                outView?.alpha = 1f
                tabTransitionAnimator = null
            }
        })

        return animator
    }

    private fun stopTabTransition() {
        val animator = tabTransitionAnimator ?: return
        if (animator.isRunning) {
            animator.end()
        }
        tabTransitionAnimator = null
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

    private fun ViewGroup?.findExistingTabView(): View? {
        val parent = this ?: return null
        val viewCount = parent.childCount
        for (childIdx in 0 until viewCount) {
            val childView = parent.getChildAt(childIdx)
            if (childView is TabView) {
                return (childView as TabView).getView()
            }
        }
        return null
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

        const val ANIMATION_DURATION = 300
        const val SITE_GLOBE = 0
        const val SITE_LOCK = 1
    }
}
