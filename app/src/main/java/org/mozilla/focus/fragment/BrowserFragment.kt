/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.fragment

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Parcelable
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewStub
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.snackbar.Snackbar
import dagger.Lazy
import org.mozilla.focus.BuildConfig
import org.mozilla.focus.R
import org.mozilla.focus.activity.MainActivity
import org.mozilla.focus.databinding.FragmentBrowserBinding
import org.mozilla.focus.locale.LocaleAwareFragment
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.navigation.ScreenNavigator.BrowserScreen
import org.mozilla.focus.screenshot.CaptureRunnable
import org.mozilla.focus.screenshot.CaptureRunnable.CaptureStateListener
import org.mozilla.focus.tabs.tabtray.TabTray
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.TelemetryWrapper.Extra_Value
import org.mozilla.focus.telemetry.TelemetryWrapper.longPressDownloadIndicator
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.FileChooseAction
import org.mozilla.focus.utils.IntentUtils
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.utils.SupportUtils
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.viewmodel.ShoppingSearchPromptViewModel
import org.mozilla.focus.viewmodel.ShoppingSearchPromptViewModel.VisibilityState
import org.mozilla.focus.viewmodel.ShoppingSearchPromptViewModel.VisibilityState.Expanded
import org.mozilla.focus.widget.BackKeyHandleable
import org.mozilla.focus.widget.FindInPage
import org.mozilla.permissionhandler.PermissionHandle
import org.mozilla.permissionhandler.PermissionHandler
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.BottomBarViewModel
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.ChromeViewModel.ScreenCaptureTelemetryData
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.content.view.BottomBar.BottomBarBehavior.Companion.slideUp
import org.mozilla.rocket.download.DownloadIndicatorIntroViewHelper.OnViewInflated
import org.mozilla.rocket.download.DownloadIndicatorIntroViewHelper.initDownloadIndicatorIntroView
import org.mozilla.rocket.download.DownloadIndicatorViewModel
import org.mozilla.rocket.extension.switchFrom
import org.mozilla.rocket.landing.PortraitComponent
import org.mozilla.rocket.landing.PortraitStateModel
import org.mozilla.rocket.permission.GeolocationPermissionController
import org.mozilla.rocket.sessions.SessionManagerObserver
import org.mozilla.rocket.sessions.SessionObserver
import org.mozilla.rocket.shopping.search.ui.ShoppingSearchActivity.Companion.getStartIntent
import org.mozilla.rocket.shopping.search.ui.adapter.ShoppingSiteItem
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.TabView.FullscreenCallback
import org.mozilla.rocket.tabs.TabsSessionProvider
import org.mozilla.rocket.tabs.utils.TabUtil
import org.mozilla.rocket.tabs.web.Download
import org.mozilla.threadutils.ThreadUtils
import org.mozilla.urlutils.UrlUtils
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Fragment for displaying the browser UI.
 */
class BrowserFragment : LocaleAwareFragment(), BrowserScreen, LifecycleOwner, BackKeyHandleable {

    @Inject
    lateinit var downloadIndicatorViewModelCreator: Lazy<DownloadIndicatorViewModel>

    @Inject
    lateinit var bottomBarViewModelCreator: Lazy<BottomBarViewModel>

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var promptMessageViewModelCreator: Lazy<ShoppingSearchPromptViewModel>
    lateinit var chromeViewModel: ChromeViewModel
    lateinit var bottomBarViewModel: BottomBarViewModel
    private lateinit var bottomBarItemAdapter: BottomBarItemAdapter
    lateinit var shoppingSearchPromptMessageViewModel: ShoppingSearchPromptViewModel
    private lateinit var shoppingSearchPromptMessageBehavior: BottomSheetBehavior<*>

    var binding: FragmentBrowserBinding? = null

    var systemVisibility = ViewUtils.SYSTEM_UI_VISIBILITY_NONE

    lateinit var findInPage: FindInPage
    lateinit var shoppingSearchViewStub: ViewStub
    lateinit var sessionManager: SessionManager
    lateinit var appBarBgTransition: TransitionDrawable
    lateinit var statusBarBgTransition: TransitionDrawable
    var webContextMenu: Dialog? = null

    val geolocationController: GeolocationPermissionController
        by lazy { GeolocationPermissionController() }

    var fullscreenCallback: FullscreenCallback? = null
    var isLoading = false

    // Set an initial WeakReference so we never have to handle loadStateListenerWeakReference being null
    // (i.e. so we can always just .get()).
    private var loadStateListenerWeakReference = WeakReference<LoadStateListener?>(null)

    @set:VisibleForTesting
    var captureStateListener: CaptureStateListener? = null

    // pending action for file-choosing
    var fileChooseAction: FileChooseAction? = null
    lateinit var permissionHandler: PermissionHandler
    private var hasPendingScreenCaptureTask = false
    private var pendingScreenCaptureTelemetryData: ScreenCaptureTelemetryData? = null
    private val sessionObserver = SessionObserver(this)
    private val managerObserver: SessionManager.Observer =
        SessionManagerObserver(this, sessionObserver)
    private var downloadIndicatorIntro: View? = null
    private var landscapeStartTime = 0L
    var loadedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        this.appComponent().inject(this)
        super.onCreate(savedInstanceState)
        bottomBarViewModel = getActivityViewModel(bottomBarViewModelCreator)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        shoppingSearchPromptMessageViewModel = getActivityViewModel(promptMessageViewModelCreator)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null) {
            permissionHandler.onRestoreInstanceState(savedInstanceState)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        permissionHandler = PermissionHandler(object : PermissionHandle {
            override fun doActionDirect(permission: String, actionId: Int, params: Parcelable?) {
                when (actionId) {
                    ACTION_DOWNLOAD -> {
                        if (getContext() == null) {
                            Log.w(
                                ScreenNavigator.BROWSER_FRAGMENT_TAG,
                                "No context to use, abort callback onDownloadStart"
                            )
                            return
                        }
                        val download = params as Download
                        if (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(
                                requireContext(),
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        ) {
                            // We do have the permission to write to the external storage. Proceed with the download.
                            queueDownload(download)
                        }
                    }
                    ACTION_PICK_FILE -> fileChooseAction?.startChooserActivity()
                    ACTION_GEO_LOCATION -> mayShowGeolocationDialog()
                    ACTION_CAPTURE -> showLoadingAndCapture(params as ScreenCaptureTelemetryData)
                    else -> throw IllegalArgumentException("Unknown actionId")
                }
            }

            private fun actionDownloadGranted(parcelable: Parcelable?) {
                queueDownload(parcelable as? Download)
            }

            private fun actionPickFileGranted() {
                fileChooseAction?.startChooserActivity()
            }

            private fun actionCaptureGranted(telemetryData: ScreenCaptureTelemetryData) {
                setPendingScreenCaptureTask(telemetryData)
            }

            private fun doActionGrantedOrSetting(actionId: Int, params: Parcelable?) {
                when (actionId) {
                    ACTION_DOWNLOAD -> actionDownloadGranted(params)
                    ACTION_PICK_FILE -> actionPickFileGranted()
                    ACTION_GEO_LOCATION -> mayShowGeolocationDialog()
                    ACTION_CAPTURE -> actionCaptureGranted(params as ScreenCaptureTelemetryData)
                    else -> throw IllegalArgumentException("Unknown actionId")
                }
            }

            override fun doActionGranted(permission: String, actionId: Int, params: Parcelable?) {
                doActionGrantedOrSetting(actionId, params)
            }

            override fun doActionSetting(permission: String, actionId: Int, params: Parcelable?) {
                doActionGrantedOrSetting(actionId, params)
            }

            override fun doActionNoPermission(
                permission: String,
                actionId: Int,
                params: Parcelable?
            ) {
                when (actionId) {
                    ACTION_DOWNLOAD -> {
                    }
                    ACTION_PICK_FILE -> fileChooseAction?.let {
                        it.cancel()
                        fileChooseAction = null
                    }
                    ACTION_GEO_LOCATION -> geolocationController.rejectGeoRequest(false)
                    ACTION_CAPTURE -> {
                    }
                    else -> throw IllegalArgumentException("Unknown actionId")
                }
            }

            override fun makeAskAgainSnackBar(actionId: Int): Snackbar {
                return PermissionHandler.makeAskAgainSnackBar(
                    this@BrowserFragment,
                    requireActivity().findViewById(R.id.container),
                    getAskAgainSnackBarString(actionId),
                    binding?.browserBottomBar
                )
            }

            private fun getAskAgainSnackBarString(actionId: Int): Int {
                return if (actionId == ACTION_DOWNLOAD || actionId == ACTION_PICK_FILE || actionId == ACTION_CAPTURE) {
                    R.string.permission_toast_storage
                } else if (actionId == ACTION_GEO_LOCATION) {
                    R.string.permission_toast_location
                } else {
                    throw IllegalArgumentException("Unknown Action")
                }
            }

            private fun getPermissionDeniedToastString(actionId: Int): Int {
                return if (actionId == ACTION_DOWNLOAD || actionId == ACTION_PICK_FILE || actionId == ACTION_CAPTURE) {
                    R.string.permission_toast_storage_deny
                } else if (actionId == ACTION_GEO_LOCATION) {
                    R.string.permission_toast_location_deny
                } else {
                    throw IllegalArgumentException("Unknown Action")
                }
            }

            override fun requestPermissions(actionId: Int) {
                when (actionId) {
                    ACTION_DOWNLOAD, ACTION_CAPTURE -> this@BrowserFragment.requestPermissions(
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        actionId
                    )
                    ACTION_PICK_FILE -> this@BrowserFragment.requestPermissions(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        actionId
                    )
                    ACTION_GEO_LOCATION -> this@BrowserFragment.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        actionId
                    )
                    else -> throw IllegalArgumentException("Unknown Action")
                }
            }

            override fun permissionDeniedToast(actionId: Int) {
                Toast.makeText(
                    getContext(),
                    getPermissionDeniedToastString(actionId),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
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

    override fun onResume() {
        sessionManager.resume()
        super.onResume()
        if (hasPendingScreenCaptureTask) {
            showLoadingAndCapture(pendingScreenCaptureTelemetryData)
            clearPendingScreenCaptureTask()
        }
    }

    fun updateURL(url: String?) {
        if (UrlUtils.isInternalErrorURL(url)) {
            return
        }
        binding?.toolbar?.displayUrl?.text = UrlUtils.stripUserInfo(url)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentBrowserBinding.inflate(inflater, container, false).also {
        this.binding = it
        shoppingSearchViewStub = it.shoppingSearchStub
    }.root

    private fun observeShoppingSearchPromptMessageViewModel() {
        shoppingSearchPromptMessageViewModel.openShoppingSearch.observe(
            viewLifecycleOwner,
            Observer {
                startActivity(getStartIntent(requireContext()))
                ScreenNavigator.get(context).popToHomeScreen(false)
            }
        )
        shoppingSearchPromptMessageViewModel.promptVisibilityState.observe(
            viewLifecycleOwner,
            Observer { visibilityState: VisibilityState? ->
                if (shoppingSearchViewStub.parent != null) {
                    setupShoppingSearchPrompt(shoppingSearchViewStub.inflate())
                }
                if (visibilityState is Expanded) {
                    changeShoppingSearchPromptMessageState(BottomSheetBehavior.STATE_EXPANDED)
                } else {
                    changeShoppingSearchPromptMessageState(BottomSheetBehavior.STATE_HIDDEN)
                }
            }
        )
        shoppingSearchPromptMessageViewModel.shoppingSiteList.observe(
            viewLifecycleOwner,
            Observer<List<ShoppingSiteItem?>> {
                shoppingSearchPromptMessageViewModel.checkShoppingSearchPromptVisibility(url)
            }
        )
    }

    private fun setupShoppingSearchPrompt(view: View) {
        shoppingSearchPromptMessageBehavior =
            BottomSheetBehavior.from(view.findViewById<CoordinatorLayout>(R.id.bottom_sheet))
                .apply {
                    setBottomSheetCallback(object : BottomSheetCallback() {
                        override fun onStateChanged(bottomSheet: View, newState: Int) {
                            when (newState) {
                                BottomSheetBehavior.STATE_EXPANDED -> shoppingSearchPromptMessageViewModel.onPromptIsShown()
                                BottomSheetBehavior.STATE_HIDDEN -> shoppingSearchPromptMessageViewModel.onPromptIsDismissed()
                                BottomSheetBehavior.STATE_DRAGGING -> shoppingSearchPromptMessageViewModel.onPromptIsDragged()
                            }
                        }

                        override fun onSlide(bottomSheet: View, slideOffset: Float) {
                            // Do nothing
                        }
                    })
                }
        view.findViewById<Button>(R.id.bottom_sheet_search).setOnClickListener {
            shoppingSearchPromptMessageViewModel.onShoppingSearchPromptButtonClicked()
        }
    }

    private fun changeShoppingSearchPromptMessageState(state: Int) {
        shoppingSearchPromptMessageBehavior.state = state
    }

    private fun observeChromeAction() {
        chromeViewModel.isTurboModeEnabled.observe(
            viewLifecycleOwner,
            Observer { enabled: Boolean ->
                setContentBlockingEnabled(enabled)
            }
        )
        chromeViewModel.isBlockImageEnabled.observe(
            viewLifecycleOwner,
            Observer { enabled: Boolean ->
                setImageBlockingEnabled(enabled)
            }
        )
        chromeViewModel.isBlockJavaScriptEnabled.observe(
            viewLifecycleOwner,
            Observer { enabled: Boolean ->
                setJavaScriptBlockingEnabled(enabled)
            }
        )
        chromeViewModel.doScreenshot.observe(
            viewLifecycleOwner,
            Observer { telemetryData: ScreenCaptureTelemetryData? ->
                permissionHandler.tryAction(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ACTION_CAPTURE,
                    telemetryData
                )
            }
        )
        chromeViewModel.refreshOrStop.observe(
            viewLifecycleOwner,
            Observer {
                if (isLoading) {
                    stop()
                } else {
                    reload()
                }
            }
        )
        chromeViewModel.goNext.observe(
            viewLifecycleOwner,
            Observer {
                if (canGoForward()) {
                    goForward()
                }
            }
        )
        chromeViewModel.goBack.observe(
            viewLifecycleOwner,
            Observer {
                if (canGoBack()) {
                    goBack()
                }
            }
        )
        chromeViewModel.showFindInPage.observe(
            viewLifecycleOwner,
            Observer {
                if (chromeViewModel.navigationState.value?.isBrowser == true) {
                    showFindInPage()
                }
            }
        )
        chromeViewModel.currentUrl.observe(
            viewLifecycleOwner,
            Observer {
                binding?.appBar?.setExpanded(true)
                binding?.browserBottomBar?.slideUp()
            }
        )
    }

    private fun observeDarkTheme() {
        chromeViewModel.isDarkTheme.observe(
            viewLifecycleOwner,
            Observer { setDarkThemeEnabled(it) }
        )
    }

    private fun setupBottomBar() {
        val browserBottomBar = binding?.browserBottomBar ?: return
        browserBottomBar.setOnItemClickListener { type, position ->
            when (type) {
                BottomBarItemAdapter.TYPE_TAB_COUNTER -> {
                    chromeViewModel.showTabTray.call()
                    TelemetryWrapper.showTabTrayToolbar(
                        Extra_Value.WEBVIEW,
                        position,
                        isInLandscape()
                    )
                }
                BottomBarItemAdapter.TYPE_MENU -> {
                    chromeViewModel.showBrowserMenu.call()
                    TelemetryWrapper.showMenuToolbar(Extra_Value.WEBVIEW, position)
                }
                BottomBarItemAdapter.TYPE_HOME -> {
                    chromeViewModel.showNewTab.call()
                    TelemetryWrapper.clickAddTabToolbar(
                        Extra_Value.WEBVIEW,
                        position,
                        isInLandscape()
                    )
                }
                BottomBarItemAdapter.TYPE_SEARCH -> {
                    chromeViewModel.showUrlInput.value = url
                    TelemetryWrapper.clickToolbarSearch(
                        Extra_Value.WEBVIEW,
                        position,
                        isInLandscape()
                    )
                }
                BottomBarItemAdapter.TYPE_CAPTURE -> chromeViewModel.onDoScreenshot(
                    ScreenCaptureTelemetryData(Extra_Value.WEBVIEW, position)
                )
                BottomBarItemAdapter.TYPE_PIN_SHORTCUT -> {
                    chromeViewModel.pinShortcut.call()
                    TelemetryWrapper.clickAddToHome(Extra_Value.WEBVIEW, position)
                }
                BottomBarItemAdapter.TYPE_BOOKMARK -> {
                    val isActivated =
                        bottomBarItemAdapter.getItem(BottomBarItemAdapter.TYPE_BOOKMARK)?.view?.isActivated == true
                    TelemetryWrapper.clickToolbarBookmark(
                        !isActivated,
                        Extra_Value.WEBVIEW,
                        position
                    )
                    chromeViewModel.toggleBookmark()
                }
                BottomBarItemAdapter.TYPE_REFRESH -> {
                    chromeViewModel.refreshOrStop.call()
                    TelemetryWrapper.clickToolbarReload(
                        Extra_Value.WEBVIEW,
                        position,
                        isInLandscape()
                    )
                }
                BottomBarItemAdapter.TYPE_SHARE -> {
                    chromeViewModel.share.call()
                    TelemetryWrapper.clickToolbarShare(
                        Extra_Value.WEBVIEW,
                        position,
                        isInLandscape()
                    )
                }
                BottomBarItemAdapter.TYPE_NEXT -> {
                    chromeViewModel.goNext.call()
                    TelemetryWrapper.clickToolbarForward(Extra_Value.WEBVIEW, position)
                }
                else -> throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
            }
        }
        browserBottomBar.setOnItemLongClickListener { type: Int, _: Int ->
            if (type == BottomBarItemAdapter.TYPE_MENU) {
                // Long press menu always show download panel
                chromeViewModel.showDownloadPanel.call()
                longPressDownloadIndicator()
                true
            } else {
                false
            }
        }
        bottomBarItemAdapter =
            BottomBarItemAdapter(browserBottomBar, BottomBarItemAdapter.Theme.Light)
        bottomBarViewModel.items.observe(
            viewLifecycleOwner,
            Observer { types: List<BottomBarItemAdapter.ItemData> ->
                bottomBarItemAdapter.setItems(types)
            }
        )
        chromeViewModel.isDarkTheme.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { isDarkTheme ->
                    bottomBarItemAdapter.setDarkTheme(isDarkTheme)
                }
            )
        chromeViewModel.tabCount.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { count: Int ->
                    bottomBarItemAdapter.setTabCount(count, true)
                }
            )
        chromeViewModel.isRefreshing.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { isRefreshing: Boolean ->
                    bottomBarItemAdapter.setRefreshing(isRefreshing)
                }
            )
        chromeViewModel.canGoForward.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { canGoForward: Boolean ->
                    bottomBarItemAdapter.setCanGoForward(canGoForward)
                }
            )
        chromeViewModel.isCurrentUrlBookmarked.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { isBookmark: Boolean ->
                    bottomBarItemAdapter.setBookmark(isBookmark)
                }
            )
        setupDownloadIndicator()
    }

    private fun isInLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun setupDownloadIndicator() {
        val downloadIndicatorViewModel = getActivityViewModel(downloadIndicatorViewModelCreator)
        downloadIndicatorViewModel.downloadIndicatorObservable.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { status: DownloadIndicatorViewModel.Status ->
                    when (status) {
                        DownloadIndicatorViewModel.Status.DOWNLOADING -> bottomBarItemAdapter.setDownloadState(
                            BottomBarItemAdapter.DOWNLOAD_STATE_DOWNLOADING
                        )
                        DownloadIndicatorViewModel.Status.UNREAD -> bottomBarItemAdapter.setDownloadState(
                            BottomBarItemAdapter.DOWNLOAD_STATE_UNREAD
                        )
                        DownloadIndicatorViewModel.Status.WARNING -> bottomBarItemAdapter.setDownloadState(
                            BottomBarItemAdapter.DOWNLOAD_STATE_WARNING
                        )
                        DownloadIndicatorViewModel.Status.DEFAULT -> bottomBarItemAdapter.setDownloadState(
                            BottomBarItemAdapter.DOWNLOAD_STATE_DEFAULT
                        )
                    }
                    val eventHistory = Settings.getInstance(activity).eventHistory
                    if (!eventHistory.contains(Settings.Event.ShowDownloadIndicatorIntro) && status !== DownloadIndicatorViewModel.Status.DEFAULT) {
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
            )
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
        observeShoppingSearchPromptMessageViewModel()
        observeDarkTheme()

        // restore WebView state
        if (savedInstanceState != null) {
            // Fragment was destroyed
            // FIXME: Obviously, only restore current tab is not enough
            val focusTab = sessionManager.focusSession
            if (focusTab != null) {
                val tabView = focusTab.engineSession?.tabView
                if (tabView != null) {
                    tabView.restoreViewState(savedInstanceState)
                } else {
                    // Focus to tab again to force initialization.
                    sessionManager.switchToTab(focusTab.id)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        permissionHandler.onActivityResult(activity, requestCode, resultCode, data)
        if (requestCode == FileChooseAction.REQUEST_CODE_CHOOSE_FILE) {
            val done =
                fileChooseAction == null || fileChooseAction?.onFileChose(resultCode, data) == true
            if (done) {
                fileChooseAction = null
            }
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

    // Workaround for full-screen WebView issue that the video doesn't fit the viewport
    // after rotating the device from portrait to landscape and vice versa. It could reduce
    // the issue happened rate by changing the video view layout size to a slight smaller size
    // then add to the full screen size again when the device is rotated.
    private fun refreshVideoContainer() {
        val videoContainer = binding?.videoContainer ?: return
        if (videoContainer.visibility == View.VISIBLE) {
            updateVideoContainerWithLayoutParams(
                FrameLayout.LayoutParams(
                    (videoContainer.height * 0.99).toInt(),
                    (videoContainer.width * 0.99).toInt()
                )
            )
            videoContainer.post {
                if (videoContainer.visibility == View.VISIBLE) {
                    updateVideoContainerWithLayoutParams(
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        }
    }

    private fun updateVideoContainerWithLayoutParams(params: FrameLayout.LayoutParams) {
        val videoContainer = binding?.videoContainer ?: return
        val fullscreenContentView = videoContainer.getChildAt(0)
        if (fullscreenContentView != null) {
            videoContainer.removeAllViews()
            videoContainer.addView(fullscreenContentView, params)
        }
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
        val current = sessionManager.focusSession
        if (current != null) {
            val es = current.engineSession
            if (es != null) {
                es.detach()
                val tabView = es.tabView
                if (tabView != null) {
                    binding?.webviewSlot?.removeView(tabView.getView())
                }
            }
        }
    }

    override fun goForeground() {
        val current = sessionManager.focusSession
        val webViewSlot = binding?.webviewSlot ?: return
        if (webViewSlot.childCount == 0 && current != null) {
            val es = current.engineSession
            if (es != null) {
                val tabView = es.tabView
                if (tabView != null) {
                    webViewSlot.addView(tabView.getView())
                }
            }
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
        permissionHandler.onSaveInstanceState(outState)
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
        geolocationController.dismissDialog()
        super.onStop()
    }

    override fun onDestroyView() {
        sessionManager.unregister(managerObserver)
        binding = null
        super.onDestroyView()
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

    private fun setJavaScriptBlockingEnabled(enabled: Boolean) {
        // TODO: Better if we can move this logic to some setting-like classes, and provider interface
        // for configuring JavaScript blocking function of each tab.
        for (session in sessionManager.getTabs()) {
            session.engineSession?.tabView?.setJavaScriptBlockingEnabled(enabled)
        }
    }

    interface LoadStateListener {
        fun isLoadingChanged(isLoading: Boolean)
    }

    /**
     * Set a (singular) LoadStateListener. Only one listener is supported at any given time. Setting
     * a new listener means any previously set listeners will be dropped. This is only intended
     * to be used by NavigationItemViewHolder. If you want to use this method for any other
     * parts of the codebase, please extend it to handle a list of listeners. (We would also need
     * to automatically clean up expired listeners from that list, probably when adding to that list.)
     *
     * @param listener The listener to notify of load state changes. Only a weak reference will be kept,
     * no more calls will be sent once the listener is garbage collected.
     */
    @VisibleForTesting
    fun setIsLoadingListener(listener: LoadStateListener?) {
        loadStateListenerWeakReference = WeakReference(listener)
    }

    private fun setPendingScreenCaptureTask(telemetryData: ScreenCaptureTelemetryData) {
        hasPendingScreenCaptureTask = true
        pendingScreenCaptureTelemetryData = telemetryData
    }

    private fun clearPendingScreenCaptureTask() {
        hasPendingScreenCaptureTask = false
        pendingScreenCaptureTelemetryData = null
    }

    private fun showLoadingAndCapture(telemetryData: ScreenCaptureTelemetryData?) {
        if (!isResumed) {
            return
        }
        clearPendingScreenCaptureTask()
        val capturingFragment = ScreenCaptureDialogFragment.newInstance()
        val portraitState = portraitStateModel
        if (portraitState != null) {
            portraitState.request(PortraitComponent.ScreenCapture)
            capturingFragment.addOnDismissListener {
                portraitState.cancelRequest(PortraitComponent.ScreenCapture)
            }
        }
        capturingFragment.show(childFragmentManager, "capturingFragment")
        // Post delay to wait for Dialog to show
        Handler().postDelayed(
            CaptureRunnable(
                context,
                this,
                capturingFragment,
                requireActivity().findViewById(R.id.container),
                telemetryData
            ),
            CAPTURE_WAIT_INTERVAL.toLong()
        )
    }

    fun updateIsLoading(isLoading: Boolean) {
        this.isLoading = isLoading
        val currentListener = loadStateListenerWeakReference.get()
        currentListener?.isLoadingChanged(isLoading)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        permissionHandler.onRequestPermissionsResult(
            context,
            requestCode,
            permissions,
            grantResults
        )
    }

    /**
     * Use Android's Download Manager to queue this download.
     */
    private fun queueDownload(download: Download?) {
        val activity: Activity? = activity
        if (activity == null || download == null) {
            return
        }
        chromeViewModel.onEnqueueDownload(download, url)
    }

    // No SafeIntent needed here because intent.getAction() is safe (SafeIntent simply calls intent.getAction()
    // without any wrapping):
    val isStartedFromExternalApp: Boolean
        get() {
            val activity = activity ?: return false

            // No SafeIntent needed here because intent.getAction() is safe (SafeIntent simply calls intent.getAction()
            // without any wrapping):
            val intent = activity.intent
            val isFromInternal = intent != null && intent.getBooleanExtra(
                IntentUtils.EXTRA_IS_INTERNAL_REQUEST,
                false
            )
            return intent != null && Intent.ACTION_VIEW == intent.action && !isFromInternal
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
            val focus = sessionManager.focusSession
            if (focus == null) {
                return false
            } else if (focus.isFromExternal || focus.hasParentTab()) {
                sessionManager.closeTab(focus.id)
            } else {
                ScreenNavigator.get(context).popToHomeScreen(true)
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

    // getUrl() is used for things like sharing the current URL. We could try to use the webview,
    // but sometimes it's null, and sometimes it returns a null URL. Sometimes it returns a data:
    // URL for error pages. The URL we show in the toolbar is (A) always correct and (B) what the
    // user is probably expecting to share, so lets use that here:
    val url: String
        get() = binding?.toolbar?.displayUrl?.text?.toString().orEmpty()

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

    interface ScreenshotCallback {
        fun onCaptureComplete(title: String?, url: String?, bitmap: Bitmap?)
    }

    fun capturePage(callback: ScreenshotCallback): Boolean {
        val currentTab = sessionManager.focusSession ?: return false
        // Failed to get WebView
        val current = currentTab.engineSession?.tabView
        if (current == null || current !is WebView) {
            return false
        }
        val webView = current as WebView
        val content = getPageBitmap(webView) ?: return false
        // Failed to capture
        callback.onCaptureComplete(current.title, current.url, content)
        return true
    }

    fun dismissAllMenus() {
        dismissWebContextMenu()
        geolocationController.dismissDialog()
    }

    private fun mayShowGeolocationDialog() {
        if (isPopupWindowAllowed) {
            geolocationController.showPermissionDialog(requireContext())
        }
    }

    private fun dismissWebContextMenu() {
        webContextMenu?.let {
            it.dismiss()
            webContextMenu = null
        }
    }

    private fun getPageBitmap(webView: WebView): Bitmap? {
        val displayMetrics = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
        return try {
            val bitmap = Bitmap.createBitmap(
                webView.width,
                (webView.contentHeight * displayMetrics.density).toInt(),
                Bitmap.Config.RGB_565
            )
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

    val isPopupWindowAllowed: Boolean
        get() = ScreenNavigator.get(context).isBrowserInForeground &&
            isAdded && !TabTray.isShowing(parentFragmentManager)

    fun getWebViewSlot(): ViewGroup? = binding?.webviewSlot

    private fun showFindInPage() {
        val binding = this.binding ?: return
        val browserBottomBar = binding?.browserBottomBar ?: return
        val focusTab = sessionManager.focusSession
        if (focusTab != null) {
            binding.appBar.setExpanded(false)
            browserBottomBar.visibility = View.INVISIBLE
            shoppingSearchViewStub.visibility = View.INVISIBLE
            binding.root.isActivated = false
            findInPage.onDismissListener = {
                binding.root.isActivated = true
                binding.appBar.setExpanded(true)
                browserBottomBar.visibility = View.VISIBLE
                shoppingSearchViewStub.visibility = View.VISIBLE
            }
            findInPage.show(focusTab)
            TelemetryWrapper.findInPage(TelemetryWrapper.FIND_IN_PAGE.OPEN_BY_MENU)
        }
    }

    private val portraitStateModel: PortraitStateModel?
        get() {
            val activity = activity ?: return null
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

    fun hideFindInPage() {
        findInPage.hide()
    }

    fun checkToShowMyShotOnBoarding() {
        chromeViewModel.checkToShowMyShotOnBoarding()
    }

    override fun getFragment(): Fragment {
        return this
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
        const val ACTION_DOWNLOAD = 0
        const val ACTION_PICK_FILE = 1
        const val ACTION_GEO_LOCATION = 2
        const val ACTION_CAPTURE = 3
        const val CAPTURE_WAIT_INTERVAL = 150
    }
}
