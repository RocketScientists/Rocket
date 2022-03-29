package org.mozilla.rocket.menu

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import dagger.Lazy
import org.mozilla.fileutils.FileUtils
import org.mozilla.focus.R
import org.mozilla.focus.databinding.BottomSheetBrowserMenuBinding
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.TelemetryWrapper.Extra_Value
import org.mozilla.focus.utils.FormatUtils
import org.mozilla.focus.utils.ScrollableBottomSheetHelper
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.MenuViewModel
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.extension.nonNullObserve
import org.mozilla.rocket.extension.switchFrom
import org.mozilla.rocket.nightmode.AdjustBrightnessDialog
import javax.inject.Inject

class BottomSheetBrowserMenuFragment : DialogFragment() {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var menuViewModelCreator: Lazy<MenuViewModel>

    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var menuViewModel: MenuViewModel
    private lateinit var bottomBarItemAdapter: BottomBarItemAdapter

    private var binding: BottomSheetBrowserMenuBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent().inject(this)
        super.onCreate(savedInstanceState)
        // overwrite android.R.style.Theme_Panel, so it looks like normal Fragment
        setStyle(STYLE_NO_TITLE, R.style.BottomSheetTheme)

        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        menuViewModel = getActivityViewModel(menuViewModelCreator)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = BottomSheetBrowserMenuBinding.inflate(inflater, container, false).also {
        binding = it
        initBottomSheet(it)
        initMenuTabs(it)
        initMenuItems(it)
        initBottomBar(it)
        observeChromeAction()
    }.root

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacksAndMessages(null)
        binding = null
    }

    private fun initBottomSheet(binding: BottomSheetBrowserMenuBinding) {
        val helperBinding = ScrollableBottomSheetHelper.Binding(
            binding.root,
            binding.container,
            binding.bottomSheet
        )

        ScrollableBottomSheetHelper.makeViewScrollable(this.requireContext(), helperBinding) {
            this.dismissAllowingStateLoss()
        }
    }

    private fun initMenuTabs(binding: BottomSheetBrowserMenuBinding) {
        chromeViewModel.hasUnreadScreenshot.observe(this) {
            binding.imgScreenshots.isActivated = it
        }

        binding.menuScreenshots.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.showScreenshots()
        }
        binding.menuBookmark.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.showBookmarks.call()
            TelemetryWrapper.clickMenuBookmark()
        }
        binding.menuHistory.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.showHistory.call()
            TelemetryWrapper.clickMenuHistory()
        }
        binding.menuDownload.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.showDownloadPanel.call()
            TelemetryWrapper.clickMenuDownload()
        }
    }

    private fun initMenuItems(binding: BottomSheetBrowserMenuBinding) {
        chromeViewModel.isTurboModeEnabled.observe(this) {
            binding.turboModeSwitch.isChecked = it
        }

        chromeViewModel.isBlockImageEnabled.observe(this) {
            binding.blockImagesSwitch.isChecked = it
        }

        chromeViewModel.isNightMode.observe(this) { nightModeSettings ->
            binding.nightModeSwitch.isChecked = nightModeSettings.isEnabled
        }

        binding.menuFindInPage.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.showFindInPage.call()
        }
        binding.menuPinShortcut.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.pinShortcut.call()
            TelemetryWrapper.clickMenuPinShortcut()
        }
        binding.menuPinSite.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.pinSite.call()
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
        binding.menuPreferences.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.checkToDriveDefaultBrowser()
            chromeViewModel.openPreference.call()
            TelemetryWrapper.clickMenuSettings()
        }
        binding.menuDelete.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            onDeleteClicked()
            TelemetryWrapper.clickMenuClearCache()
        }
        binding.menuExit.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.exitApp.call()
            TelemetryWrapper.clickMenuExit()
        }
    }

    private fun onDeleteClicked() {
        val context = context ?: return
        val diff = FileUtils.clearCache(context)
        val stringId =
            if (diff < 0) R.string.message_clear_cache_fail else R.string.message_cleared_cached
        val msg = context.getString(stringId, FormatUtils.getReadableStringFromFileSize(diff))
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun observeChromeAction() {
        chromeViewModel.showAdjustBrightness.observe(this) { showAdjustBrightness() }
    }

    private fun showAdjustBrightness() {
        val context = context ?: return
        ContextCompat.startActivity(
            context,
            AdjustBrightnessDialog.Intents.getStartIntentFromMenu(context),
            null
        )
    }

    private fun initBottomBar(binding: BottomSheetBrowserMenuBinding) {
        val bottomBar = binding.menuBottomBar

        bottomBar.setOnItemClickListener { type, position ->
            dismissAllowingStateLoss()
            handleBottomBarClickEvent(type, position)
            sendBottomBarClickTelemetry(type, position)
        }

        bottomBarItemAdapter = BottomBarItemAdapter(bottomBar, BottomBarItemAdapter.Theme.Light)
        menuViewModel.bottomItems.nonNullObserve(this) { bottomItems ->
            bottomBarItemAdapter.setItems(bottomItems)
            hidePinShortcutButtonIfNotSupported()
        }

        chromeViewModel.tabCount.switchFrom(menuViewModel.bottomItems)
            .observe(this) { bottomBarItemAdapter.setTabCount(it ?: 0) }
        chromeViewModel.isRefreshing.switchFrom(menuViewModel.bottomItems)
            .observe(this) { bottomBarItemAdapter.setRefreshing(it == true) }
        chromeViewModel.canGoForward.switchFrom(menuViewModel.bottomItems)
            .observe(this) { bottomBarItemAdapter.setCanGoForward(it == true) }
        chromeViewModel.canGoBack.switchFrom(menuViewModel.bottomItems)
            .observe(this) { bottomBarItemAdapter.setCanGoBack(it == true) }
        chromeViewModel.isCurrentUrlBookmarked.switchFrom(menuViewModel.bottomItems)
            .observe(this) { bottomBarItemAdapter.setBookmark(it == true) }
    }

    private fun handleBottomBarClickEvent(type: ItemType, position: Int) {
        when (type) {
            ItemType.TAB_COUNTER -> chromeViewModel.showTabTray.call()
            ItemType.MENU -> chromeViewModel.showBrowserMenu.call()
            ItemType.HOME -> chromeViewModel.showNewTab.call()
            ItemType.SEARCH -> chromeViewModel.showUrlInput.call()
            ItemType.PIN_SHORTCUT -> chromeViewModel.pinShortcut.call()
            ItemType.BOOKMARK -> chromeViewModel.toggleBookmark()
            ItemType.REFRESH -> chromeViewModel.refreshOrStop.call()
            ItemType.SHARE -> chromeViewModel.share.call()
            ItemType.NEXT -> chromeViewModel.goNext.call()
            ItemType.BACK -> chromeViewModel.goBack.call()
            ItemType.CAPTURE -> chromeViewModel.onDoScreenshot(
                ChromeViewModel.ScreenCaptureTelemetryData(Extra_Value.MENU, position)
            )
            ItemType.PRIVATE_HOME,
            ItemType.DELETE,
            ItemType.TRACKER,
            ItemType.SHOPPING_SEARCH ->
                throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
        }
    }

    private fun sendBottomBarClickTelemetry(type: ItemType, position: Int) {
        // move Telemetry to ScreenCaptureTask doInBackground() cause need to init category first.
        when (type) {
            ItemType.TAB_COUNTER -> TelemetryWrapper.showTabTrayToolbar(Extra_Value.MENU, position)
            ItemType.MENU -> TelemetryWrapper.showMenuToolbar(Extra_Value.MENU, position)
            ItemType.HOME -> TelemetryWrapper.clickAddTabToolbar(Extra_Value.MENU, position)
            ItemType.SEARCH -> TelemetryWrapper.clickToolbarSearch(Extra_Value.MENU, position)
            ItemType.PIN_SHORTCUT -> TelemetryWrapper.clickAddToHome(Extra_Value.MENU, position)
            ItemType.REFRESH -> TelemetryWrapper.clickToolbarReload(Extra_Value.MENU, position)
            ItemType.SHARE -> TelemetryWrapper.clickToolbarShare(Extra_Value.MENU, position)
            ItemType.NEXT -> TelemetryWrapper.clickToolbarForward(Extra_Value.MENU, position)
            ItemType.BACK -> TelemetryWrapper.clickToolbarBack(position)
            ItemType.BOOKMARK -> {
                val nullableItem = bottomBarItemAdapter.getItem(ItemType.BOOKMARK)
                val isActivated = nullableItem?.view?.isActivated == true
                TelemetryWrapper.clickToolbarBookmark(!isActivated, Extra_Value.MENU, position)
            }
            ItemType.CAPTURE,
            ItemType.PRIVATE_HOME,
            ItemType.DELETE,
            ItemType.TRACKER,
            ItemType.SHOPPING_SEARCH -> Unit
        }
    }

    private fun hidePinShortcutButtonIfNotSupported() {
        val context = context ?: return
        val requestPinShortcutSupported =
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        if (!requestPinShortcutSupported) {
            val pinShortcutItem = bottomBarItemAdapter.getItem(ItemType.PIN_SHORTCUT)
            pinShortcutItem?.view?.apply {
                visibility = View.GONE
            }
        }
    }

    /**
     * Extension to set callback that posts delay click event to wait the clicking feedback shows.
     */
    private fun View.setOnClickDelayedListener(action: () -> Unit) {
        this.setOnClickListener {
            uiHandler.postDelayed({ action() }, 150)
        }
    }

    companion object {
        const val TAG = "BottomSheetBrowserMenuFragment"

        fun createInstance(): BottomSheetBrowserMenuFragment {
            return BottomSheetBrowserMenuFragment()
        }

        fun show(supportFragmentManager: FragmentManager) {
            createInstance().show(supportFragmentManager, TAG)
        }

        fun dismiss(supportFragmentManager: FragmentManager) {
            val fragment = getDisplayedFragment(supportFragmentManager)
            fragment?.dismissAllowingStateLoss()
        }

        fun getScreenshotMenuButton(supportFragmentManager: FragmentManager): View? {
            val fragment = getDisplayedFragment(supportFragmentManager)
            return fragment?.binding?.menuScreenshots
        }

        private fun getDisplayedFragment(
            supportFragmentManager: FragmentManager
        ): BottomSheetBrowserMenuFragment? {
            val taggedFragment = supportFragmentManager.findFragmentByTag(TAG) ?: return null
            return taggedFragment as? BottomSheetBrowserMenuFragment ?: return null
        }
    }
}
