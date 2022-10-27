package org.mozilla.rocket.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.snackbar.Snackbar
import dagger.Lazy
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentHomeBinding
import org.mozilla.focus.locale.LocaleAwareFragment
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.DialogUtils
import org.mozilla.focus.utils.FirebaseHelper
import org.mozilla.focus.utils.FirebaseHelper.stopAndClose
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.rocket.adapter.AdapterDelegatesManager
import org.mozilla.rocket.adapter.DelegateAdapter
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.component.RocketLauncherActivity
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.download.DownloadIndicatorViewModel
import org.mozilla.rocket.extension.showFxToast
import org.mozilla.rocket.extension.switchMap
import org.mozilla.rocket.home.logoman.ui.LogoManNotification
import org.mozilla.rocket.home.topsites.domain.PinTopSiteUseCase
import org.mozilla.rocket.home.topsites.ui.AddNewTopSitesActivity
import org.mozilla.rocket.home.topsites.ui.Site
import org.mozilla.rocket.home.topsites.ui.SitePage
import org.mozilla.rocket.home.topsites.ui.SitePageAdapterDelegate
import org.mozilla.rocket.home.topsites.ui.SiteViewHolder.Companion.TOP_SITE_LONG_CLICK_TARGET
import org.mozilla.rocket.home.ui.MenuButton.Companion.DOWNLOAD_STATE_DEFAULT
import org.mozilla.rocket.home.ui.MenuButton.Companion.DOWNLOAD_STATE_DOWNLOADING
import org.mozilla.rocket.home.ui.MenuButton.Companion.DOWNLOAD_STATE_UNREAD
import org.mozilla.rocket.home.ui.MenuButton.Companion.DOWNLOAD_STATE_WARNING
import org.mozilla.rocket.settings.defaultbrowser.ui.DefaultBrowserHelper
import org.mozilla.rocket.settings.defaultbrowser.ui.DefaultBrowserPreferenceViewModel
import org.mozilla.rocket.theme.ThemeManager
import org.mozilla.rocket.util.ToastMessage
import org.mozilla.rocket.util.setCurrentItem
import javax.inject.Inject

class HomeFragment : LocaleAwareFragment(), ScreenNavigator.HomeScreen {

    @Inject
    lateinit var homeViewModelCreator: Lazy<HomeViewModel>

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var downloadIndicatorViewModelCreator: Lazy<DownloadIndicatorViewModel>

    @Inject
    lateinit var defaultBrowserPreferenceViewModelCreator: Lazy<DefaultBrowserPreferenceViewModel>

    @Inject
    lateinit var appContext: Context

    var binding: FragmentHomeBinding? = null

    private lateinit var homeViewModel: HomeViewModel
    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var downloadIndicatorViewModel: DownloadIndicatorViewModel
    private lateinit var defaultBrowserPreferenceViewModel: DefaultBrowserPreferenceViewModel
    private lateinit var themeManager: ThemeManager
    private lateinit var topSitesAdapter: DelegateAdapter
    private lateinit var defaultBrowserHelper: DefaultBrowserHelper

    private val topSitesPageChangeCallback = object : OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            homeViewModel.onTopSitesPagePositionChanged(position)
        }
    }

    private lateinit var toastObserver: Observer<ToastMessage>

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent().inject(this)
        super.onCreate(savedInstanceState)
        homeViewModel = getActivityViewModel(homeViewModelCreator)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        downloadIndicatorViewModel = getActivityViewModel(downloadIndicatorViewModelCreator)
        defaultBrowserPreferenceViewModel =
            getActivityViewModel(defaultBrowserPreferenceViewModelCreator)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentHomeBinding.inflate(inflater, container, false).also {
        // toastObserver is an instance which lives for long time
        // do not directly store `appContext` reference inside toastObserver
        // otherwise, toastObserver stores `homeFragment.appContext` - which leaks HomeFragment
        val applicationContext = appContext
        toastObserver = Observer { applicationContext.showFxToast(it) }
        homeViewModel.showToast.observeForever(toastObserver)

        this.binding = it
    }.root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val binding = this.binding ?: return
        themeManager = (context as ThemeManager.ThemeHost).themeManager
        initSearchToolBar(binding)
        initBackgroundView(binding)
        initTopSites(binding)
        initLogoManNotification(binding)
        observeDarkTheme(binding)
        observeAddNewTopSites(binding)
        observeSetDefaultBrowser()
        observeActions(binding)

        Looper.myQueue().addIdleHandler {
            FirebaseHelper.retrieveTrace("coldStart")?.stopAndClose()
            false
        }
    }

    private fun initSearchToolBar(binding: FragmentHomeBinding) {
        binding.homeFragmentFakeInput.setOnClickListener {
            chromeViewModel.showUrlInput.call()
            TelemetryWrapper.showSearchBarHome()
        }
        binding.homeFragmentMenuButton.apply {
            setOnClickListener {
                chromeViewModel.showHomeMenu.call()
                TelemetryWrapper.showMenuHome()
            }
            setOnLongClickListener {
                chromeViewModel.showDownloadPanel.call()
                TelemetryWrapper.longPressDownloadIndicator()
                true
            }
        }
        binding.homeFragmentTabCounter.setOnClickListener {
            chromeViewModel.showTabTray.call()
            TelemetryWrapper.showTabTrayHome()
        }
        chromeViewModel.tabCount.observe(
            viewLifecycleOwner,
            Observer {
                setTabCount(it ?: 0)
            }
        )

        chromeViewModel.isPrivateBrowsingActive.observe(
            viewLifecycleOwner,
            Observer {
                binding.privateModeButton.isActivated = it
            }
        )
        binding.privateModeButton.setOnClickListener { homeViewModel.onPrivateModeButtonClicked() }
        homeViewModel.openPrivateMode.observe(
            viewLifecycleOwner,
            Observer {
                chromeViewModel.togglePrivateMode.call()
            }
        )
        homeViewModel.shouldShowNewMenuItemHint.switchMap {
            if (it) {
                MutableLiveData<DownloadIndicatorViewModel.Status>().apply { DownloadIndicatorViewModel.Status.DEFAULT }
            } else {
                downloadIndicatorViewModel.downloadIndicatorObservable
            }
        }.observe(
            viewLifecycleOwner,
            Observer {
                binding.homeFragmentMenuButton.apply {
                    when (it) {
                        DownloadIndicatorViewModel.Status.DOWNLOADING -> setDownloadState(
                            DOWNLOAD_STATE_DOWNLOADING
                        )
                        DownloadIndicatorViewModel.Status.UNREAD -> setDownloadState(
                            DOWNLOAD_STATE_UNREAD
                        )
                        DownloadIndicatorViewModel.Status.WARNING -> setDownloadState(
                            DOWNLOAD_STATE_WARNING
                        )
                        else -> setDownloadState(DOWNLOAD_STATE_DEFAULT)
                    }
                }
            }
        )
        homeViewModel.shouldShowNewMenuItemHint.observe(
            viewLifecycleOwner,
            Observer {
                binding.homeFragmentMenuButton.findViewById<View>(R.id.menu_red_dot).isVisible = it
            }
        )
    }

    private fun initBackgroundView(binding: FragmentHomeBinding) {
        themeManager.subscribeThemeChange(binding.homeBackground)
        val backgroundGestureDetector =
            GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent?): Boolean {
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent?): Boolean {
                        return homeViewModel.onBackgroundViewDoubleTap()
                    }

                    override fun onLongPress(e: MotionEvent?) {
                        homeViewModel.onBackgroundViewLongPress()
                    }
                }
            )
        binding.homeBackground.setOnTouchListener { _, event ->
            backgroundGestureDetector.onTouchEvent(event)
        }
        homeViewModel.toggleBackgroundColor.observe(
            viewLifecycleOwner,
            Observer {
                val themeSet = themeManager.toggleNextTheme()
                TelemetryWrapper.changeThemeTo(themeSet.name)
            }
        )
        homeViewModel.resetBackgroundColor.observe(
            viewLifecycleOwner,
            Observer {
                themeManager.resetDefaultTheme()
                TelemetryWrapper.resetThemeToDefault()
            }
        )
        homeViewModel.homeBackgroundColorThemeClicked.observe(
            viewLifecycleOwner,
            Observer { themeSet ->
                themeManager.setCurrentTheme(themeSet)
            }
        )
    }

    private fun initTopSites(binding: FragmentHomeBinding) {
        topSitesAdapter = DelegateAdapter(
            AdapterDelegatesManager().apply {
                add(
                    SitePage::class,
                    R.layout.item_top_site_page,
                    SitePageAdapterDelegate(homeViewModel, chromeViewModel)
                )
            }
        )
        binding.mainList.apply {
            adapter = this@HomeFragment.topSitesAdapter
            registerOnPageChangeCallback(topSitesPageChangeCallback)
        }
        var savedTopSitesPagePosition = homeViewModel.topSitesPageIndex.value
        homeViewModel.run {
            sitePages.observe(
                viewLifecycleOwner,
                Observer {
                    binding.pageIndicator.setSize(it.size)
                    topSitesAdapter.setData(it)
                    savedTopSitesPagePosition?.let { savedPosition ->
                        savedTopSitesPagePosition = null
                        binding.mainList.setCurrentItem(savedPosition, false)
                    }
                }
            )
            topSitesPageIndex.observe(
                viewLifecycleOwner,
                Observer {
                    binding.pageIndicator.setSelection(it)
                }
            )
            openBrowser.observe(
                viewLifecycleOwner,
                Observer { url ->
                    ScreenNavigator.get(context).showBrowserScreen(url, true, false)
                }
            )
            showTopSiteMenu.observe(
                viewLifecycleOwner,
                Observer { (site, position) ->
                    site as Site.UrlSite.RemovableSite
                    val anchorView =
                        binding.mainList.findViewWithTag<View>(TOP_SITE_LONG_CLICK_TARGET)
                            .apply { tag = null }
                    val allowToPin = !site.isPinned
                    showTopSiteMenu(anchorView, allowToPin, site, position)
                }
            )
            showAddTopSiteMenu.observe(
                viewLifecycleOwner,
                Observer {
                    val anchorView =
                        binding.mainList.findViewWithTag<View>(TOP_SITE_LONG_CLICK_TARGET)
                            .apply { tag = null }
                    showAddTopSiteMenu(anchorView)
                }
            )
        }
        chromeViewModel.clearBrowsingHistory.observe(
            viewLifecycleOwner,
            Observer {
                homeViewModel.onClearBrowsingHistory()
            }
        )
    }

    private fun observeDarkTheme(binding: FragmentHomeBinding) {
        chromeViewModel.isDarkTheme.observe(
            viewLifecycleOwner,
            Observer { darkThemeEnable ->
                ViewUtils.updateStatusBarStyle(!darkThemeEnable, requireActivity().window)
                topSitesAdapter.notifyDataSetChanged()
                binding.homeBackground.setDarkTheme(darkThemeEnable)
                binding.arcView.setDarkTheme(darkThemeEnable)
                binding.arcPanel.setDarkTheme(darkThemeEnable)
                binding.searchPanel.setDarkTheme(darkThemeEnable)
                binding.homeFragmentFakeInput.setDarkTheme(darkThemeEnable)
                binding.homeFragmentFakeInputIcon.setDarkTheme(darkThemeEnable)
                binding.homeFragmentFakeInputText.setDarkTheme(darkThemeEnable)
                binding.homeFragmentTabCounter.setDarkTheme(darkThemeEnable)
                binding.homeFragmentMenuButton.setDarkTheme(darkThemeEnable)
                binding.homeFragmentMenuButton.setDarkTheme(darkThemeEnable)
                binding.privateModeButton.setDarkTheme(darkThemeEnable)
            }
        )
    }

    override fun onStart() {
        super.onStart()
        homeViewModel.onPageForeground()
    }

    override fun onResume() {
        super.onResume()
        defaultBrowserPreferenceViewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        defaultBrowserPreferenceViewModel.onPause()
    }

    override fun onStop() {
        super.onStop()
        homeViewModel.onPageBackground()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        homeViewModel.showToast.removeObserver(toastObserver)
        this.binding?.let {
            themeManager.unsubscribeThemeChange(it.homeBackground)
            it.mainList.unregisterOnPageChangeCallback(topSitesPageChangeCallback)
        }
        this.binding = null
    }

    override fun getFragment(): Fragment = this

    override fun onUrlInputScreenVisible(visible: Boolean) {
        if (visible) {
            chromeViewModel.onShowHomePageUrlInput()
        } else {
            chromeViewModel.onDismissHomePageUrlInput()
        }
    }

    override fun applyLocale() {
        binding?.homeFragmentFakeInputText?.text = getString(R.string.home_search_bar_text)
    }

    fun notifyAddNewTopSiteResult(pinTopSiteResult: PinTopSiteUseCase.PinTopSiteResult) {
        homeViewModel.onAddNewTopSiteResult(pinTopSiteResult)
    }

    private fun showTopSiteMenu(anchorView: View, pinEnabled: Boolean, site: Site, position: Int) {
        PopupMenu(anchorView.context, anchorView, Gravity.CLIP_HORIZONTAL)
            .apply {
                menuInflater.inflate(R.menu.menu_top_site_item, menu)
                menu.findItem(R.id.pin)?.apply {
                    isVisible = pinEnabled
                }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.pin -> homeViewModel.onPinTopSiteClicked(site, position)
                        R.id.remove -> homeViewModel.onRemoveTopSiteClicked(site, position)
                        else -> throw IllegalStateException("Unhandled menu item")
                    }

                    true
                }
            }
            .show()
    }

    private fun showAddTopSiteMenu(anchorView: View) {
        PopupMenu(anchorView.context, anchorView, Gravity.CLIP_HORIZONTAL)
            .apply {
                menuInflater.inflate(R.menu.menu_add_top_site_item, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.add_top_sites -> homeViewModel.onAddTopSiteContextMenuClicked()
                        else -> throw IllegalStateException("Unhandled menu item")
                    }
                    true
                }
            }
            .show()
    }

    private fun setTabCount(count: Int, animationEnabled: Boolean = false) {
        binding?.homeFragmentTabCounter?.apply {
            if (animationEnabled) {
                setCountWithAnimation(count)
            } else {
                setCount(count)
            }
            if (count > 0) {
                isEnabled = true
                alpha = 1f
            } else {
                isEnabled = false
                alpha = 0.3f
            }
        }
    }

    private fun showAddNewTopSitesPage() {
        activity?.let {
            it.startActivityForResult(
                AddNewTopSitesActivity.getStartIntent(it),
                AddNewTopSitesActivity.REQUEST_CODE_ADD_NEW_TOP_SITES
            )
        }
    }

    private fun initLogoManNotification(binding: FragmentHomeBinding) {
        homeViewModel.logoManNotification.observe(
            viewLifecycleOwner,
            Observer {
                it?.let { (notification, animate) ->
                    showLogoManNotification(notification, animate)
                }
            }
        )
        homeViewModel.hideLogoManNotification.observe(
            viewLifecycleOwner,
            Observer {
                hideLogoManNotification()
            }
        )
        binding.logoManNotification.setNotificationActionListener(
            object : LogoManNotification.NotificationActionListener {
                override fun onNotificationClick() {
                    homeViewModel.onLogoManNotificationClicked()
                }

                override fun onNotificationDismiss() {
                    homeViewModel.onLogoManDismissed()
                }
            }
        )
    }

    private fun showLogoManNotification(
        notification: LogoManNotification.Notification,
        animate: Boolean
    ) {
        binding?.logoManNotification?.showNotification(notification, animate)
        homeViewModel.onLogoManShown()
    }

    private fun hideLogoManNotification() {
        binding?.logoManNotification?.isVisible = false
    }

    private fun restoreStatusBarColor() {
        activity?.window?.statusBarColor = Color.TRANSPARENT
    }

    private fun setOnboardingStatusBarColor() {
        activity?.let {
            it.window.statusBarColor = ContextCompat.getColor(it, R.color.paletteBlack50)
        }
    }

    private fun observeAddNewTopSites(binding: FragmentHomeBinding) {
        homeViewModel.openAddNewTopSitesPage.observe(
            viewLifecycleOwner,
            Observer {
                showAddNewTopSitesPage()
            }
        )
        homeViewModel.addNewTopSiteFullyPinned.observe(
            viewLifecycleOwner,
            Observer {
                context?.let {
                    Toast.makeText(it, R.string.add_top_site_toast, Toast.LENGTH_LONG).show()
                }
            }
        )
        chromeViewModel.addNewTopSiteMenuClicked.observe(
            viewLifecycleOwner,
            Observer {
                homeViewModel.onAddTopSiteMenuClicked()
            }
        )
        homeViewModel.addNewTopSiteSuccess.observe(
            viewLifecycleOwner,
            Observer { page ->
                page?.let {
                    scrollToTopSitePage(binding, it)
                }
                Snackbar.make(
                    binding.mainList,
                    getText(R.string.add_top_site_snackbar_1),
                    Snackbar.LENGTH_LONG
                )
                    .setAction(R.string.add_top_site_button) { homeViewModel.onAddMoreTopSiteSnackBarClicked() }
                    .show()
            }
        )
        homeViewModel.addExistingTopSite.observe(
            viewLifecycleOwner,
            Observer { page ->
                page?.let {
                    scrollToTopSitePage(binding, it)
                }
                Snackbar.make(
                    binding.mainList,
                    getText(R.string.add_top_site_snackbar_2),
                    Snackbar.LENGTH_LONG
                )
                    .setAction(R.string.add_top_site_button) { homeViewModel.onAddMoreTopSiteSnackBarClicked() }
                    .show()
            }
        )
    }

    private fun observeSetDefaultBrowser() {
        activity?.let { activity ->
            defaultBrowserHelper = DefaultBrowserHelper(activity, defaultBrowserPreferenceViewModel)
            homeViewModel.tryToSetDefaultBrowser.observe(
                viewLifecycleOwner,
                Observer {
                    defaultBrowserPreferenceViewModel.performAction()
                }
            )
            defaultBrowserPreferenceViewModel.openDefaultAppsSettings.observe(
                viewLifecycleOwner,
                Observer { defaultBrowserHelper.openDefaultAppsSettings() }
            )
            defaultBrowserPreferenceViewModel.openAppDetailSettings.observe(
                viewLifecycleOwner,
                Observer { defaultBrowserHelper.openAppDetailSettings() }
            )
            defaultBrowserPreferenceViewModel.openSumoPage.observe(
                viewLifecycleOwner,
                Observer { defaultBrowserHelper.openSumoPage() }
            )
            defaultBrowserPreferenceViewModel.triggerWebOpen.observe(
                viewLifecycleOwner,
                Observer { defaultBrowserHelper.triggerWebOpen() }
            )
            defaultBrowserPreferenceViewModel.openDefaultAppsSettingsTutorialDialog.observe(
                viewLifecycleOwner,
                Observer {
                    DialogUtils.showGoToSystemAppsSettingsDialog(
                        activity,
                        defaultBrowserPreferenceViewModel
                    )
                }
            )
            defaultBrowserPreferenceViewModel.openUrlTutorialDialog.observe(
                viewLifecycleOwner,
                Observer {
                    DialogUtils.showOpenUrlDialog(
                        activity,
                        defaultBrowserPreferenceViewModel
                    )
                }
            )
            defaultBrowserPreferenceViewModel.successToSetDefaultBrowser.observe(
                viewLifecycleOwner,
                Observer { defaultBrowserHelper.showSuccessMessage() }
            )
            defaultBrowserPreferenceViewModel.failToSetDefaultBrowser.observe(
                viewLifecycleOwner,
                Observer { defaultBrowserHelper.showFailMessage() }
            )
        }
    }

    private fun scrollToTopSitePage(binding: FragmentHomeBinding, page: Int) =
        binding.mainList.postDelayed({ binding.mainList.setCurrentItem(page, 300) }, 100)

    private fun observeActions(binding: FragmentHomeBinding) {
        homeViewModel.executeUriAction.observe(
            viewLifecycleOwner,
            Observer { action ->
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(action),
                        appContext,
                        RocketLauncherActivity::class.java
                    )
                )
            }
        )
        homeViewModel.showKeyboard.observe(
            viewLifecycleOwner,
            Observer {
                Looper.myQueue().addIdleHandler {
                    if (!isStateSaved) {
                        binding.homeFragmentFakeInput.performClick()
                    }
                    false
                }
            }
        )
        chromeViewModel.themeSettingMenuClicked.observe(
            viewLifecycleOwner,
            Observer {
                homeViewModel.onThemeSettingMenuClicked()
            }
        )
        homeViewModel.showThemeSetting.observe(
            viewLifecycleOwner,
            Observer {
                activity?.let {
                    DialogUtils.showThemeSettingDialog(it, homeViewModel)
                }
            }
        )
        homeViewModel.showSetAsDefaultBrowserOnboarding.observe(
            viewLifecycleOwner,
            Observer {
                activity?.let {
                    DialogUtils.showSetAsDefaultBrowserDialog(
                        it,
                        { homeViewModel.onSetAsDefaultBrowserClicked() },
                        { homeViewModel.onCancelSetAsDefaultBrowserClicked() }
                    )
                }
            }
        )
    }
}
