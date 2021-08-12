package org.mozilla.rocket.browser

import android.content.res.Configuration
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.OnLifecycleEvent
import dagger.Lazy
import org.mozilla.focus.databinding.FragmentBrowserBinding
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.Settings
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.BottomBarItemAdapter.DownloadState
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemType
import org.mozilla.rocket.chrome.BottomBarItemAdapter.Theme
import org.mozilla.rocket.chrome.BottomBarViewModel
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.download.DownloadIndicatorIntroViewHelper.initDownloadIndicatorIntroView
import org.mozilla.rocket.download.DownloadIndicatorViewModel
import org.mozilla.rocket.extension.switchFrom
import javax.inject.Inject
import org.mozilla.focus.telemetry.TelemetryWrapper.Extra_Value.WEBVIEW as EXTRA_WEB_VIEW
import org.mozilla.rocket.download.DownloadIndicatorViewModel.Status as IndicatorStatus

class BottomBarController(private val fragment: BrowserFragment) : LifecycleObserver {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var bottomBarViewModelCreator: Lazy<BottomBarViewModel>

    @Inject
    lateinit var indicatorViewModelCreator: Lazy<DownloadIndicatorViewModel>

    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var bottomBarViewModel: BottomBarViewModel
    private lateinit var downloadIndicatorViewModel: DownloadIndicatorViewModel

    private var bottomBarItemAdapter: BottomBarItemAdapter? = null
    private var downloadIndicatorIntro: View? = null

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onViewCreated() {
        fragment.appComponent().inject(this)
        downloadIndicatorViewModel = fragment.getActivityViewModel(indicatorViewModelCreator)
        bottomBarViewModel = fragment.getActivityViewModel(bottomBarViewModelCreator)
        chromeViewModel = fragment.getActivityViewModel(chromeViewModelCreator)

        val binding = fragment.binding ?: return
        setupBottomBar(binding)
        setupDownloadIndicator(binding)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroyView() {
        bottomBarItemAdapter = null
    }

    fun screenRotateToLandscape(isLandscape: Boolean) {
        bottomBarViewModel.onScreenRotatedToLandscape(isLandscape)
    }

    fun refreshViewModel() {
        bottomBarViewModel.refresh()
    }

    fun dismissDownloadIndicatorIntroView() {
        downloadIndicatorIntro?.visibility = View.GONE
        downloadIndicatorIntro = null
    }

    private fun setupBottomBar(binding: FragmentBrowserBinding) {
        val browserBottomBar = binding.browserBottomBar
        bottomBarItemAdapter = BottomBarItemAdapter(browserBottomBar, Theme.Light)

        browserBottomBar.setOnItemClickListener { type, position ->
            updateChromeViewModelForBottomBarClick(type, position)
            sendTelemetryForBottomBarClick(type, position)
        }

        browserBottomBar.setOnItemLongClickListener { type: ItemType, _: Int ->
            if (type == ItemType.MENU) {
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
            bottomBarItemAdapter?.setItems(items)
        }

        // This equals to of using switchMap. LiveData of tabCount will be RECREATED
        // via calling `map`, once `bottomBarViewModel.items` is updated.
        // bottomBarViewModel.items.switchMap { chromeViewModel.tabCount.map { it } }
        //     .observeOnViewLifecycle { count: Int -> ... }
        // Namely, `setTabCount` will be called whenever bottomBarViewModel.items are changed
        // regardless chromeViewModel.tabCount.value is changed or not.
        chromeViewModel.tabCount.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { count: Int ->
                bottomBarItemAdapter?.setTabCount(count, true)
            }
        chromeViewModel.isDarkTheme.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { isDarkTheme ->
                bottomBarItemAdapter?.setDarkTheme(isDarkTheme)
            }
        chromeViewModel.isRefreshing.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { isRefreshing: Boolean ->
                bottomBarItemAdapter?.setRefreshing(isRefreshing)
                fragment.updateLoadingState(isRefreshing)
            }
        chromeViewModel.canGoForward.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { canGoForward: Boolean ->
                bottomBarItemAdapter?.setCanGoForward(canGoForward)
            }
        chromeViewModel.isCurrentUrlBookmarked.switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { isBookmark: Boolean ->
                bottomBarItemAdapter?.setBookmark(isBookmark)
            }
    }

    private fun setupDownloadIndicator(binding: FragmentBrowserBinding) {
        downloadIndicatorViewModel
            .downloadIndicatorObservable
            .switchFrom(bottomBarViewModel.items)
            .observeOnViewLifecycle { status: DownloadIndicatorViewModel.Status ->
                val downloadState = when (status) {
                    IndicatorStatus.DOWNLOADING -> DownloadState.DOWNLOADING
                    IndicatorStatus.UNREAD -> DownloadState.UNREAD
                    IndicatorStatus.WARNING -> DownloadState.WARNING
                    IndicatorStatus.DEFAULT -> DownloadState.DEFAULT
                }
                bottomBarItemAdapter?.setDownloadState(downloadState)

                if (downloadState == DownloadState.DEFAULT) {
                    return@observeOnViewLifecycle
                }

                val eventHistory = Settings.getInstance(fragment.activity).eventHistory
                // if Intro has showed before, return
                if (eventHistory.contains(Settings.Event.ShowDownloadIndicatorIntro)) {
                    return@observeOnViewLifecycle
                }

                eventHistory.add(Settings.Event.ShowDownloadIndicatorIntro)
                val menuView = bottomBarItemAdapter?.getItem(ItemType.MENU)?.view
                    ?: return@observeOnViewLifecycle

                initDownloadIndicatorIntroView(fragment, menuView, binding.root) { introView ->
                    downloadIndicatorIntro = introView
                }
            }
    }

    private fun updateChromeViewModelForBottomBarClick(t: ItemType, position: Int) = when (t) {
        ItemType.TAB_COUNTER -> chromeViewModel.showTabTray.call()
        ItemType.MENU -> chromeViewModel.showBrowserMenu.call()
        ItemType.HOME -> chromeViewModel.showNewTab.call()
        ItemType.SEARCH -> chromeViewModel.showUrlInput.value = fragment.chromeUrl
        ItemType.PIN_SHORTCUT -> chromeViewModel.pinShortcut.call()
        ItemType.BOOKMARK -> chromeViewModel.toggleBookmark()
        ItemType.REFRESH -> chromeViewModel.refreshOrStop.call()
        ItemType.SHARE -> chromeViewModel.share.call()
        ItemType.NEXT -> chromeViewModel.goNext.call()
        ItemType.CAPTURE -> chromeViewModel.onDoScreenshot(
            ChromeViewModel.ScreenCaptureTelemetryData(
                TelemetryWrapper.Extra_Value.WEBVIEW,
                position
            )
        )
        else -> throw IllegalArgumentException("Unhandled bottom bar item, type: $t")
    }

    private fun sendTelemetryForBottomBarClick(type: ItemType, position: Int) {
        when (type) {
            ItemType.TAB_COUNTER ->
                TelemetryWrapper.showTabTrayToolbar(EXTRA_WEB_VIEW, position, isInLandscape())
            ItemType.MENU ->
                TelemetryWrapper.showMenuToolbar(EXTRA_WEB_VIEW, position)
            ItemType.HOME ->
                TelemetryWrapper.clickAddTabToolbar(EXTRA_WEB_VIEW, position, isInLandscape())
            ItemType.SEARCH ->
                TelemetryWrapper.clickToolbarSearch(EXTRA_WEB_VIEW, position, isInLandscape())
            ItemType.PIN_SHORTCUT ->
                TelemetryWrapper.clickAddToHome(EXTRA_WEB_VIEW, position)
            ItemType.REFRESH ->
                TelemetryWrapper.clickToolbarReload(EXTRA_WEB_VIEW, position, isInLandscape())
            ItemType.SHARE ->
                TelemetryWrapper.clickToolbarShare(EXTRA_WEB_VIEW, position, isInLandscape())
            ItemType.NEXT ->
                TelemetryWrapper.clickToolbarForward(EXTRA_WEB_VIEW, position)
            ItemType.BOOKMARK -> {
                val isActivated = isBookmarkItemActivated()
                TelemetryWrapper.clickToolbarBookmark(isActivated, EXTRA_WEB_VIEW, position)
            }
            ItemType.CAPTURE -> Unit
            else -> throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
        }
    }

    private fun isBookmarkItemActivated(): Boolean {
        val bookmarkItem = bottomBarItemAdapter?.getItem(ItemType.BOOKMARK)
        return bookmarkItem?.view?.isActivated == true
    }

    private fun isInLandscape(): Boolean {
        return fragment.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * A helper function to observer a LiveData via View's lifecycle
     */
    private fun <X> LiveData<X>.observeOnViewLifecycle(
        lifecycleOwner: LifecycleOwner = fragment.viewLifecycleOwner,
        observer: Observer<X>
    ) {
        this.observe(lifecycleOwner, observer)
    }
}
