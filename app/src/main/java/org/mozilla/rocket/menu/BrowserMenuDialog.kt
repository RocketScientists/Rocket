package org.mozilla.rocket.menu

import android.content.Context
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ScrollView
import android.widget.Toast
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.Observer
import dagger.Lazy
import org.mozilla.fileutils.FileUtils
import org.mozilla.focus.R
import org.mozilla.focus.databinding.BottomSheetBrowserMenuBinding
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.FormatUtils
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemType
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.MenuViewModel
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.extension.nonNullObserve
import org.mozilla.rocket.extension.switchFrom
import org.mozilla.rocket.nightmode.AdjustBrightnessDialog
import org.mozilla.rocket.widget.LifecycleBottomSheetDialog
import javax.inject.Inject

class BrowserMenuDialog : LifecycleBottomSheetDialog {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var menuViewModelCreator: Lazy<MenuViewModel>

    private lateinit var menuViewModel: MenuViewModel
    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var bottomBarItemAdapter: BottomBarItemAdapter

    private lateinit var binding: BottomSheetBrowserMenuBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    constructor(context: Context) : super(context)
    constructor(context: Context, @StyleRes theme: Int) : super(context, theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent().inject(this)
        super.onCreate(savedInstanceState)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        menuViewModel = getActivityViewModel(menuViewModelCreator)

        initLayout()
        observeChromeAction()
        setCancelable(false)
        setCanceledOnTouchOutside(true)
    }

    override fun dismiss() {
        if (::binding.isInitialized) {
            binding.scrollView.fullScroll(ScrollView.FOCUS_UP)
        }
        super.dismiss()
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun initLayout() {
        binding = BottomSheetBrowserMenuBinding.inflate(layoutInflater, null, false)
        binding.contentLayout.apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        resources.getDimension(R.dimen.menu_corner_radius)
                    )
                }
            }
            clipToOutline = true
        }
        initMenuTabs()
        initMenuItems()
        initBottomBar()
        setContentView(binding.root)
    }

    private fun initMenuTabs() {
        binding.contentLayout.apply {
            chromeViewModel.hasUnreadScreenshot.observe(this@BrowserMenuDialog) {
                binding.imgScreenshots.isActivated = it
            }

            binding.menuScreenshots.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.showScreenshots()
                }
            }
            binding.menuBookmark.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.showBookmarks.call()
                    TelemetryWrapper.clickMenuBookmark()
                }
            }
            binding.menuHistory.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.showHistory.call()
                    TelemetryWrapper.clickMenuHistory()
                }
            }
            binding.menuDownload.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.showDownloadPanel.call()
                    TelemetryWrapper.clickMenuDownload()
                }
            }
        }
    }

    private fun initMenuItems() {
        binding.contentLayout.apply {
            chromeViewModel.isTurboModeEnabled.observe(this@BrowserMenuDialog) {
                binding.turboModeSwitch.isChecked = it
            }

            chromeViewModel.isBlockImageEnabled.observe(this@BrowserMenuDialog) {
                binding.blockImagesSwitch.isChecked = it
            }

            chromeViewModel.isNightMode.observe(this@BrowserMenuDialog) { nightModeSettings ->
                binding.nightModeSwitch.isChecked = nightModeSettings.isEnabled
            }

            binding.menuFindInPage.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.showFindInPage.call()
                }
            }
            binding.menuPinShortcut.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.pinShortcut.call()
                    TelemetryWrapper.clickMenuPinShortcut()
                }
            }
            binding.menuNightMode.setOnClickListener {
                chromeViewModel.adjustNightMode()
            }
            binding.menuTurboMode.setOnClickListener { binding.turboModeSwitch.toggle() }
            binding.turboModeSwitch.setOnCheckedChangeListener { _, isChecked ->
                val needToUpdate = isChecked != (chromeViewModel.isTurboModeEnabled.value == true)
                if (needToUpdate) {
                    chromeViewModel.onTurboModeToggled()
                }
            }
            binding.menuBlockImg.setOnClickListener { binding.blockImagesSwitch.toggle() }
            binding.blockImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
                val needToUpdate = isChecked != (chromeViewModel.isBlockImageEnabled.value == true)
                if (needToUpdate) {
                    chromeViewModel.onBlockImageToggled()
                }
            }
            binding.nightModeSwitch.setOnCheckedChangeListener { _, isChecked ->
                val needToUpdate =
                    isChecked != (chromeViewModel.isNightMode.value?.isEnabled == true)
                if (needToUpdate) {
                    chromeViewModel.onNightModeToggled()
                }
            }
            binding.menuPreferences.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.checkToDriveDefaultBrowser()
                    chromeViewModel.openPreference.call()
                    TelemetryWrapper.clickMenuSettings()
                }
            }
            binding.menuDelete.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    onDeleteClicked()
                    TelemetryWrapper.clickMenuClearCache()
                }
            }
            binding.menuExit.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.exitApp.call()
                    TelemetryWrapper.clickMenuExit()
                }
            }
        }
    }

    private fun onDeleteClicked() {
        val diff = FileUtils.clearCache(context)
        val stringId =
            if (diff < 0) R.string.message_clear_cache_fail else R.string.message_cleared_cached
        val msg = context.getString(stringId, FormatUtils.getReadableStringFromFileSize(diff))
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun observeChromeAction() {
        chromeViewModel.showAdjustBrightness.observe(this, Observer { showAdjustBrightness() })
    }

    private fun showAdjustBrightness() {
        ContextCompat.startActivity(
            context,
            AdjustBrightnessDialog.Intents.getStartIntentFromMenu(context),
            null
        )
    }

    private fun initBottomBar() {
        val bottomBar = binding.menuBottomBar
        bottomBar.setOnItemClickListener { type, position ->
            cancel()
            when (type) {
                ItemType.TAB_COUNTER -> {
                    chromeViewModel.showTabTray.call()
                    TelemetryWrapper.showTabTrayToolbar(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                }
                ItemType.MENU -> {
                    chromeViewModel.showBrowserMenu.call()
                    TelemetryWrapper.showMenuToolbar(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                }
                ItemType.HOME -> {
                    chromeViewModel.showNewTab.call()
                    TelemetryWrapper.clickAddTabToolbar(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                }
                ItemType.SEARCH -> {
                    chromeViewModel.showUrlInput.call()
                    TelemetryWrapper.clickToolbarSearch(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                }
                ItemType.CAPTURE -> chromeViewModel.onDoScreenshot(
                    ChromeViewModel.ScreenCaptureTelemetryData(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                )
                ItemType.PIN_SHORTCUT -> {
                    chromeViewModel.pinShortcut.call()
                    TelemetryWrapper.clickAddToHome(TelemetryWrapper.Extra_Value.MENU, position)
                }
                ItemType.BOOKMARK -> {
                    val nullableItem = bottomBarItemAdapter.getItem(ItemType.BOOKMARK)
                    val isActivated = nullableItem?.view?.isActivated == true
                    TelemetryWrapper.clickToolbarBookmark(
                        !isActivated,
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                    chromeViewModel.toggleBookmark()
                }
                ItemType.REFRESH -> {
                    chromeViewModel.refreshOrStop.call()
                    TelemetryWrapper.clickToolbarReload(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                }
                ItemType.SHARE -> {
                    chromeViewModel.share.call()
                    TelemetryWrapper.clickToolbarShare(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                }
                ItemType.NEXT -> {
                    chromeViewModel.goNext.call()
                    TelemetryWrapper.clickToolbarForward(
                        TelemetryWrapper.Extra_Value.MENU,
                        position
                    )
                }
                ItemType.BACK -> {
                    chromeViewModel.goBack.call()
                    TelemetryWrapper.clickToolbarBack(position)
                }
                ItemType.PRIVATE_HOME,
                ItemType.DELETE,
                ItemType.TRACKER,
                ItemType.SHOPPING_SEARCH ->
                    throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
            } // move Telemetry to ScreenCaptureTask doInBackground() cause we need to init category first.
        }
        bottomBarItemAdapter = BottomBarItemAdapter(bottomBar, BottomBarItemAdapter.Theme.Light)
        menuViewModel.bottomItems.nonNullObserve(this) { bottomItems ->
            bottomBarItemAdapter.setItems(bottomItems)
            hidePinShortcutButtonIfNotSupported()
        }

        chromeViewModel.tabCount.switchFrom(menuViewModel.bottomItems)
            .observe(this, Observer { bottomBarItemAdapter.setTabCount(it ?: 0) })
        chromeViewModel.isRefreshing.switchFrom(menuViewModel.bottomItems)
            .observe(this, Observer { bottomBarItemAdapter.setRefreshing(it == true) })
        chromeViewModel.canGoForward.switchFrom(menuViewModel.bottomItems)
            .observe(this, Observer { bottomBarItemAdapter.setCanGoForward(it == true) })
        chromeViewModel.canGoBack.switchFrom(menuViewModel.bottomItems)
            .observe(this, Observer { bottomBarItemAdapter.setCanGoBack(it == true) })
        chromeViewModel.isCurrentUrlBookmarked.switchFrom(menuViewModel.bottomItems)
            .observe(this, Observer { bottomBarItemAdapter.setBookmark(it == true) })
    }

    private fun hidePinShortcutButtonIfNotSupported() {
        val requestPinShortcutSupported =
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        if (!requestPinShortcutSupported) {
            val pinShortcutItem =
                bottomBarItemAdapter.getItem(ItemType.PIN_SHORTCUT)
            pinShortcutItem?.view?.apply {
                visibility = View.GONE
            }
        }
    }

    /**
     * Post delay click event to wait the clicking feedback shows
     */
    private fun postDelayClickEvent(action: () -> Unit) {
        uiHandler.postDelayed({ action() }, 150)
    }
}
