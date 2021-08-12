package org.mozilla.rocket.privately.browse

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import dagger.Lazy
import mozilla.components.browser.engine.system.SystemEngineView
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.HitResult
import mozilla.components.concept.engine.LifecycleObserver
import org.mozilla.focus.BuildConfig
import org.mozilla.focus.FocusApplication
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentPrivateBrowserBinding
import org.mozilla.focus.locale.LocaleAwareFragment
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.web.BrowsingSession
import org.mozilla.focus.widget.AnimatedProgressBar
import org.mozilla.focus.widget.BackKeyHandleable
import org.mozilla.permissionhandler.PermissionHandle
import org.mozilla.permissionhandler.PermissionHandler
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemType
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.PrivateBottomBarViewModel
import org.mozilla.rocket.content.app
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.content.view.BottomBar
import org.mozilla.rocket.extension.nonNullObserve
import org.mozilla.rocket.extension.switchFrom
import org.mozilla.rocket.extension.updateTrackingProtectionPolicy
import org.mozilla.rocket.menu.PrivateWebContextMenu
import org.mozilla.rocket.tabs.web.Download
import org.mozilla.rocket.tabs.web.DownloadCallback
import org.mozilla.threadutils.ThreadUtils
import org.mozilla.urlutils.UrlUtils
import javax.inject.Inject

private const val SITE_GLOBE = 0
private const val SITE_LOCK = 1
private const val ACTION_DOWNLOAD = 0

class BrowserFragment :
    LocaleAwareFragment(),
    ScreenNavigator.BrowserScreen,
    BackKeyHandleable {

    @Inject
    lateinit var privateBottomBarViewModelCreator: Lazy<PrivateBottomBarViewModel>

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    private val sessionManager: SessionManager by lazy {
        app().sessionManager
    }
    private lateinit var permissionHandler: PermissionHandler
    private lateinit var bottomBarItemAdapter: BottomBarItemAdapter
    private lateinit var chromeViewModel: ChromeViewModel

    private var binding: FragmentPrivateBrowserBinding? = null

    private lateinit var tabViewSlot: ViewGroup
    private lateinit var engineView: EngineView
    private lateinit var displayUrlView: TextView
    private lateinit var progressView: AnimatedProgressBar
    private lateinit var siteIdentity: ImageView

    private lateinit var trackerPopup: TrackerPopup

    private var lastSession: Session? = null

    private var systemVisibility = ViewUtils.SYSTEM_UI_VISIBILITY_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent().inject(this)
        super.onCreate(savedInstanceState)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentPrivateBrowserBinding.inflate(inflater, container, false).also {
        this.binding = it
    }.root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val fragmentActivity = activity
        if (fragmentActivity == null) {
            if (BuildConfig.DEBUG) {
                throw RuntimeException("No activity to use")
            }
        }
    }

    override fun onViewCreated(view: View, savedState: Bundle?) {
        super.onViewCreated(view, savedState)

        setupBottomBar(view)

        displayUrlView = view.findViewById(R.id.display_url)
        displayUrlView.setOnClickListener {
            chromeViewModel.showUrlInput.setValue(chromeViewModel.currentUrl.value)
        }

        siteIdentity = view.findViewById(R.id.site_identity)

        tabViewSlot = view.findViewById(R.id.tab_view_slot)
        progressView = view.findViewById(R.id.progress)

        attachEngineView(tabViewSlot)

        initTrackerView(view)

        monitorTrackerBlocked { count -> updateTrackerBlockedCount(count) }

        view.findViewById<View>(R.id.browser_container)
            .setOnApplyWindowInsetsListener { v, insets ->
                (v.layoutParams as FrameLayout.LayoutParams).topMargin = insets.systemWindowInsetTop
                insets
            }

        sessionManager.register(sessionManagerObserver)
        sessionManager.selectedSession?.let {
            it.register(sessionObserver)
            lastSession = it
        }

        observeChromeAction()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        this.binding = null
        sessionManager.unregister(sessionManagerObserver)
        lastSession?.unregister(sessionObserver)
        unregisterForContextMenu(engineView.asView())
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        permissionHandler = PermissionHandler(object : PermissionHandle {
            override fun doActionDirect(permission: String?, actionId: Int, params: Parcelable?) {

                this@BrowserFragment.context?.also {
                    val download = params as Download

                    if (PackageManager.PERMISSION_GRANTED ==
                        ContextCompat.checkSelfPermission(it, WRITE_EXTERNAL_STORAGE)
                    ) {
                        // We do have the permission to write to the external storage. Proceed with the download.
                        queueDownload(download)
                    }
                } ?: run {
                    Log.e("BrowserFragment.kt", "No context to use, abort callback onDownloadStart")
                }
            }

            fun actionDownloadGranted(parcelable: Parcelable?) {
                val download = parcelable as Download
                queueDownload(download)
            }

            override fun doActionGranted(permission: String?, actionId: Int, params: Parcelable?) {
                actionDownloadGranted(params)
            }

            override fun doActionSetting(permission: String?, actionId: Int, params: Parcelable?) {
                actionDownloadGranted(params)
            }

            override fun doActionNoPermission(
                permission: String?,
                actionId: Int,
                params: Parcelable?
            ) {
            }

            override fun makeAskAgainSnackBar(actionId: Int): Snackbar {
                activity?.also {
                    return PermissionHandler.makeAskAgainSnackBar(
                        this@BrowserFragment,
                        it.findViewById(R.id.container),
                        R.string.permission_toast_storage,
                        it.findViewById(R.id.browser_bottom_bar)
                    )
                }
                throw IllegalStateException("No Activity to show Snackbar.")
            }

            override fun permissionDeniedToast(actionId: Int) {
                Toast.makeText(
                    getContext(),
                    R.string.permission_toast_storage_deny,
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun requestPermissions(actionId: Int) {
                this@BrowserFragment.requestPermissions(
                    arrayOf(WRITE_EXTERNAL_STORAGE),
                    actionId
                )
            }

            private fun queueDownload(download: Download?) {
                activity?.let {
                    download?.let {
                        chromeViewModel.onEnqueueDownload(
                            it,
                            displayUrlView.text.toString(),
                            shouldShowInDownloadList = false
                        )
                    }
                }
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        trackerPopup.dismiss()

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding?.toolbar?.root?.visibility = View.GONE
            binding?.browserBottomBar?.visibility = View.GONE
        } else {
            if (sessionManager.selectedSession?.fullScreenMode == false) {
                binding?.toolbar?.root?.visibility = View.VISIBLE
                binding?.browserBottomBar?.visibility = View.VISIBLE
            }
        }

        refreshVideoContainer()
    }

    // Workaround for full-screen WebView issue that the video doesn't fit the viewport
    // after rotating the device from portrait to landscape and vice versa. It could reduce
    // the issue happened rate by changing the video view layout size to a slight smaller size
    // then add to the full screen size again when the device is rotated.
    private fun refreshVideoContainer() {
        val binding = this.binding ?: return
        if (binding.tabViewSlot.visibility == View.VISIBLE) {
            updateVideoContainerWithLayoutParams(
                FrameLayout.LayoutParams(
                    (binding.tabViewSlot.height * 0.99).toInt(),
                    (binding.tabViewSlot.width * 0.99).toInt()
                )
            )
            binding.tabViewSlot.post {
                if (binding.tabViewSlot.visibility == View.VISIBLE) {
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
        val binding = this.binding ?: return
        val fullscreenContentView: View? = binding.tabViewSlot.getChildAt(0)
        if (fullscreenContentView != null) {
            binding.tabViewSlot.removeAllViews()
            binding.tabViewSlot.addView(fullscreenContentView, params)
        }
    }

    private fun updateContainerBehavior(isFullScreenMode: Boolean) {
        val params = tabViewSlot.layoutParams as CoordinatorLayout.LayoutParams
        // When in fullscreen mode we don't show appbar and bottom bar, so disable appbar behavior
        params.behavior = if (isFullScreenMode) null else AppBarLayout.ScrollingViewBehavior()
        tabViewSlot.layoutParams = params
        tabViewSlot.requestLayout()

        view?.findViewById<View>(R.id.browser_container)
            ?.setOnApplyWindowInsetsListener { v, insets ->
                // When in full screen mode we don't need any extra top margin,
                // whereas we need extra top margin to prevent website's button area is covered
                (v.layoutParams as FrameLayout.LayoutParams).topMargin =
                    if (isFullScreenMode) 0 else insets.systemWindowInsetTop
                insets
            }
    }

    override fun applyLocale() {
        // We create and destroy a new WebView here to force the internal state of WebView to know
        // about the new language. See issue #666.
        val unneeded = WebView(context)
        unneeded.destroy()
    }

    override fun onBackPressed(): Boolean {
        sessionManager.selectedSession?.let {
            if (it.fullScreenMode) {
                sessionManager.getOrCreateEngineSession(it).exitFullScreenMode()
                return true
            }
        }
        if (sessionManager.selectedSession?.canGoBack == true) {
            goBack()
            return true
        }
        val selectedSession = sessionManager.selectedSession
        sessionManager.remove()
        if (selectedSession?.source == Session.Source.CUSTOM_TAB) {
            activity?.finishAndRemoveTask()
        } else {
            ScreenNavigator.get(activity).popToHomeScreen(true)
        }
        chromeViewModel.dropCurrentPage.call()
        return true
    }

    override fun getFragment(): Fragment {
        return this
    }

    override fun switchToTab(tabId: String) {
        // Do nothing in private mode
    }

    override fun goForeground() {
        // Do nothing
    }

    override fun goBackground() {
        // Do nothing
    }

    override fun loadUrl(
        url: String,
        openNewTab: Boolean,
        isFromExternal: Boolean,
        onViewReadyCallback: Runnable?
    ) {
        if (url.isNotBlank()) {
            displayUrlView.text = url
            val selectedSession = sessionManager.selectedSession
            if (selectedSession == null) {
                val source = if (isFromExternal) {
                    Session.Source.CUSTOM_TAB
                } else {
                    Session.Source.NONE
                }
                val newSession = Session(url, source = source)
                sessionManager.add(newSession)
                engineView.render(sessionManager.getOrCreateEngineSession(newSession))
            } else {
                sessionManager.getOrCreateEngineSession(selectedSession).loadUrl(url)
            }

            ThreadUtils.postToMainThread(onViewReadyCallback)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        permissionHandler.onRequestPermissionsResult(
            context,
            requestCode,
            permissions,
            grantResults
        )
    }

    private fun goBack() = sessionManager.selectedSession?.let {
        sessionManager.getEngineSession()?.goBack()
    }

    private fun goForward() = sessionManager.selectedSession?.let {
        sessionManager.getEngineSession()?.goForward()
    }

    private fun stop() = sessionManager.selectedSession?.let {
        sessionManager.getEngineSession()?.stopLoading()
    }

    private fun reload() = sessionManager.selectedSession?.let {
        sessionManager.getEngineSession()?.reload()
    }

    private fun onTrackerButtonClicked() {
        view?.let { parentView -> trackerPopup.show(parentView) }
    }

    private fun onDeleteClicked() {
        sessionManager.removeSessions()
        chromeViewModel.dropCurrentPage.call()
        ScreenNavigator.get(activity).popToHomeScreen(true)
    }

    private fun attachEngineView(parentView: ViewGroup) {
        engineView = app().engine.createView(requireContext())
        lifecycle.addObserver(LifecycleObserver(engineView))
        registerForContextMenu(engineView.asView())
        parentView.addView(engineView.asView())
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        if (v is SystemEngineView && v.onLongClick(v)) {
            return
        }
        super.onCreateContextMenu(menu, v, menuInfo)
    }

    private fun setupBottomBar(rootView: View) {
        val bottomBar = rootView.findViewById<BottomBar>(R.id.browser_bottom_bar)
        bottomBar.setOnItemClickListener { type, position ->
            when (type) {
                ItemType.SEARCH -> chromeViewModel.showUrlInput.setValue(
                    chromeViewModel.currentUrl.value
                )
                ItemType.PIN_SHORTCUT -> chromeViewModel.pinShortcut.call()
                ItemType.REFRESH -> chromeViewModel.refreshOrStop.call()
                ItemType.SHARE -> chromeViewModel.share.call()
                ItemType.NEXT -> chromeViewModel.goNext.call()
                ItemType.PRIVATE_HOME -> {
                    chromeViewModel.togglePrivateMode.call()
                    TelemetryWrapper.togglePrivateMode(true)
                }
                ItemType.DELETE -> onDeleteClicked()
                ItemType.TRACKER -> onTrackerButtonClicked()
                else ->
                    throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
            }
        }
        bottomBarItemAdapter =
            BottomBarItemAdapter(bottomBar, BottomBarItemAdapter.Theme.PrivateMode)
        val bottomBarViewModel = getActivityViewModel(privateBottomBarViewModelCreator)
        bottomBarViewModel.items.nonNullObserve(this) {
            bottomBarItemAdapter.setItems(it)
            bottomBarItemAdapter.endPrivateHomeAnimation()
            bottomBarItemAdapter.setTrackerSwitch(isTurboModeEnabled(rootView.context))
        }

        chromeViewModel.isRefreshing.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { bottomBarItemAdapter.setRefreshing(it == true) }
            )
        chromeViewModel.canGoForward.switchFrom(bottomBarViewModel.items)
            .observe(
                viewLifecycleOwner,
                Observer { bottomBarItemAdapter.setCanGoForward(it == true) }
            )

        binding?.browserContainer?.setOnKeyboardVisibilityChangedListener { isKeyboardVisible ->
            val isLandscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            bottomBar.isVisible = !isKeyboardVisible && !isLandscape
        }
    }

    private fun initTrackerView(parentView: View) {
        trackerPopup = TrackerPopup(parentView.context)
        trackerPopup.setSwitchToggled(isTurboModeEnabled(requireContext()))
        trackerPopup.onSwitchToggled = { enabled ->
            app().settings.privateBrowsingSettings.setTurboMode(enabled)
            setTrackingProtectionEnabled(enabled)
            // TODO: move to Session.Observer.onTrackerBlockingEnabledChanged
            // for now the callback has a bug in version 0.52.0
            bottomBarItemAdapter.setTrackerSwitch(enabled)
            stop()
            reload()
        }
    }

    private fun setTrackingProtectionEnabled(enabled: Boolean) {
        if (enabled) {
            EngineSession.TrackingProtectionPolicy.all()
        } else {
            null
        }.let { policy ->
            app().engineSettings.trackingProtectionPolicy = policy
            sessionManager.updateTrackingProtectionPolicy(policy)
        }
    }

    private fun isTurboModeEnabled(context: Context): Boolean {
        val appContext = (context.applicationContext as FocusApplication)
        return appContext.settings.privateBrowsingSettings.shouldUseTurboMode()
    }

    private fun monitorTrackerBlocked(onUpdate: (Int) -> Unit) {
        BrowsingSession.getInstance().blockedTrackerCount.observe(
            viewLifecycleOwner,
            Observer {
                val count = it ?: return@Observer
                onUpdate(count)
            }
        )
    }

    private fun updateTrackerBlockedCount(count: Int) {
        bottomBarItemAdapter.setTrackerBadgeEnabled(count > 0)
        trackerPopup.blockedCount = count
    }

    private fun observeChromeAction() {
        chromeViewModel.refreshOrStop.observe(
            viewLifecycleOwner,
            Observer {
                if (chromeViewModel.isRefreshing.value == true) {
                    stop()
                } else {
                    reload()
                }
            }
        )
        chromeViewModel.goNext.observe(
            viewLifecycleOwner,
            Observer {
                if (chromeViewModel.canGoForward.value == true) {
                    goForward()
                }
            }
        )
    }

    private val sessionManagerObserver = object : SessionManager.Observer {
        override fun onSessionAdded(session: Session) {
            chromeViewModel.onTabCountChanged(sessionManager.size)
        }

        override fun onSessionRemoved(session: Session) {
            session.unregister(sessionObserver)
            chromeViewModel.onTabCountChanged(sessionManager.size)
        }

        override fun onSessionSelected(session: Session) {
            lastSession?.unregister(sessionObserver)
            session.register(sessionObserver)
            lastSession = session

            chromeViewModel.run {
                onFocusedUrlChanged(session.url)
                onFocusedTitleChanged(session.title)
                onNavigationStateChanged(session.canGoBack, session.canGoForward)
            }
        }
    }

    private val sessionObserver = object : Session.Observer {
        override fun onDownload(
            session: Session,
            download: mozilla.components.browser.session.Download
        ): Boolean {
            activity.let {
                if (it == null || !it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return false
                }
            }

            val d = Download(
                download.url,
                download.fileName,
                download.userAgent,
                "",
                download.contentType,
                download.contentLength ?: 0,
                false
            )
            permissionHandler.tryAction(
                this@BrowserFragment,
                WRITE_EXTERNAL_STORAGE,
                ACTION_DOWNLOAD,
                d
            )
            // Workaround to fix the failure on the second download
            chromeViewModel.refreshOrStop.call()

            return true
        }

        override fun onFullScreenChanged(session: Session, enabled: Boolean) {
            val isLandscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (enabled) {
                if (!isLandscape) {
                    binding?.toolbar?.root?.visibility = View.GONE
                    binding?.browserBottomBar?.visibility = View.GONE
                }

                updateContainerBehavior(isFullScreenMode = true)

                // Switch to immersive mode: Hide system bars other UI controls
                systemVisibility = ViewUtils.switchToImmersiveMode(activity)
            } else {
                if (!isLandscape) {
                    binding?.toolbar?.root?.visibility = View.VISIBLE
                    binding?.browserBottomBar?.visibility = View.VISIBLE
                }

                updateContainerBehavior(isFullScreenMode = false)

                if (systemVisibility != ViewUtils.SYSTEM_UI_VISIBILITY_NONE) {
                    ViewUtils.exitImmersiveMode(systemVisibility, activity)
                }
            }
        }

        override fun onLoadingStateChanged(session: Session, loading: Boolean) {
            if (loading) {
                chromeViewModel.onPageLoadingStarted()
                BrowsingSession.getInstance().resetTrackerCount()
            } else {
                chromeViewModel.onPageLoadingStopped()
            }
        }

        override fun onLongPress(session: Session, hitResult: HitResult): Boolean {
            activity?.let {
                PrivateWebContextMenu.show(
                    true,
                    it,
                    PrivateDownloadCallback(this@BrowserFragment),
                    hitResult.toHitTarget()
                )
                return true
            }
            return false
        }

        override fun onNavigationStateChanged(
            session: Session,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            chromeViewModel.onNavigationStateChanged(canGoBack, canGoForward)
        }

        override fun onProgress(session: Session, progress: Int) {
            progressView.progress = progress
        }

        override fun onSecurityChanged(session: Session, securityInfo: Session.SecurityInfo) {
            val level = if (securityInfo.secure) SITE_LOCK else SITE_GLOBE
            siteIdentity.setImageLevel(level)
        }

        override fun onTitleChanged(session: Session, title: String) {
            chromeViewModel.onFocusedTitleChanged(title)
            session.let {
                if (displayUrlView.text.toString() != it.url) {
                    displayUrlView.text = it.url
                }
            }
        }

        override fun onTrackerBlocked(session: Session, blocked: String, all: List<String>) {
            BrowsingSession.getInstance().setBlockedTrackerCount(all.size)
        }

        override fun onUrlChanged(session: Session, url: String) {
            chromeViewModel.onFocusedUrlChanged(url)
            if (!UrlUtils.isInternalErrorURL(url)) {
                displayUrlView.text = url
            }
        }
    }

    class PrivateDownloadCallback(val fragment: BrowserFragment) : DownloadCallback {
        override fun onDownloadStart(download: Download) {
            fragment.activity?.let {
                if (!it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return
                }
            }

            fragment.permissionHandler.tryAction(
                fragment,
                WRITE_EXTERNAL_STORAGE,
                ACTION_DOWNLOAD,
                download
            )
        }
    }
}

private fun HitResult.toHitTarget(): PrivateWebContextMenu.HitTarget {
    val isLink: Boolean
    val linkURL: String
    var isImage = false
    var imageURL: String? = null
    when (this) {
        is HitResult.IMAGE -> {
            isLink = false
            linkURL = src
            isImage = true
            imageURL = src
        }
        is HitResult.IMAGE_SRC -> {
            isLink = true
            linkURL = uri
            isImage = true
            imageURL = src
        }
        else -> {
            isLink = true
            linkURL = src
        }
    }

    return PrivateWebContextMenu.HitTarget(isLink, linkURL, isImage, imageURL)
}
