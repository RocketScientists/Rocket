package org.mozilla.rocket.menu

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import dagger.Lazy
import org.mozilla.fileutils.FileUtils
import org.mozilla.focus.R
import org.mozilla.focus.databinding.BottomSheetHomeMenuBinding
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.FormatUtils
import org.mozilla.focus.utils.ScrollableBottomSheetHelper
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.MenuViewModel
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.nightmode.AdjustBrightnessDialog
import org.mozilla.rocket.shopping.search.ui.ShoppingSearchActivity
import javax.inject.Inject

class BottomSheetHomeMenuFragment : DialogFragment() {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var menuViewModelCreator: Lazy<MenuViewModel>

    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var menuViewModel: MenuViewModel

    private var binding: BottomSheetHomeMenuBinding? = null

    private val uiHandler = Handler(Looper.getMainLooper())

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
    ): View = BottomSheetHomeMenuBinding.inflate(inflater, container, false).also {
        binding = it
        initBottomSheet(it)
        initMenuTabs(it)
        initMenuItems(it)
        observeChromeAction()
    }.root

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacksAndMessages(null)
        binding = null
    }

    private fun initBottomSheet(binding: BottomSheetHomeMenuBinding) {
        val helperBinding = ScrollableBottomSheetHelper.Binding(
            binding.root,
            binding.container,
            binding.bottomSheet
        )

        ScrollableBottomSheetHelper.makeViewScrollable(this.requireContext(), helperBinding) {
            this.dismissAllowingStateLoss()
        }
    }

    private fun initMenuTabs(binding: BottomSheetHomeMenuBinding) {
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

    private fun initMenuItems(binding: BottomSheetHomeMenuBinding) {
        chromeViewModel.isNightMode.observe(this) { nightModeSettings ->
            binding.nightModeSwitch.isChecked = nightModeSettings.isEnabled
        }
        menuViewModel.isHomeScreenShoppingSearchEnabled.observe(this) {
            binding.btnPrivateBrowsing.isVisible = !it
            binding.menuSmartShoppingSearch.isVisible = it
        }
        // TODO: how to re-enable this?
        // chromeViewModel.isPrivateBrowsingActive.observe(this) {
        //     // we removed this image, and use `drawableStart` instead
        //     // binding.imgPrivateMode.isActivated = it
        // }
        menuViewModel.shouldShowNewMenuItemHint.observe(this) {
            if (it) {
                showNewItemHint()
                menuViewModel.onNewMenuItemDisplayed()
            }
        }

        binding.btnPrivateBrowsing.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.togglePrivateMode.call()
            TelemetryWrapper.togglePrivateMode(true)
        }
        binding.menuSmartShoppingSearch.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            showShoppingSearch()
        }
        binding.menuNightMode.setOnClickDelayedListener {
            chromeViewModel.adjustNightMode()
        }
        binding.nightModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            val isNightModeSwitchEnabled = (chromeViewModel.isNightMode.value?.isEnabled == true)
            val needToUpdate = isChecked != isNightModeSwitchEnabled
            if (needToUpdate) {
                chromeViewModel.onNightModeToggled()
            }
        }
        binding.menuAddTopSites.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.onAddNewTopSiteMenuClicked()
            TelemetryWrapper.clickMenuAddTopsite()
        }
        binding.menuThemes.setOnClickDelayedListener {
            dismissAllowingStateLoss()
            chromeViewModel.onThemeSettingMenuClicked()
            TelemetryWrapper.clickMenuTheme()
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

    private fun showNewItemHint() {
        val binding = binding ?: return
        binding.addTopSitesRedDot.visibility = View.VISIBLE
        binding.themesRedDot.visibility = View.VISIBLE
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
        chromeViewModel.showAdjustBrightness.observe(this) { showAdjustBrightnessDialog() }
    }

    private fun showAdjustBrightnessDialog() {
        val context = context ?: return
        val openDialogIntent = AdjustBrightnessDialog.Intents.getStartIntentFromMenu(context)
        ContextCompat.startActivity(context, openDialogIntent, null)
    }

    private fun showShoppingSearch() {
        val context = context ?: return
        context.startActivity(ShoppingSearchActivity.getStartIntent(context))
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
        const val TAG = "BottomSheetHomeMenuFragment"

        fun createInstance(): BottomSheetHomeMenuFragment {
            return BottomSheetHomeMenuFragment()
        }

        fun show(supportFragmentManager: FragmentManager) {
            createInstance().show(supportFragmentManager, TAG)
        }

        fun dismiss(supportFragmentManager: FragmentManager) {
            val taggedFragment = supportFragmentManager.findFragmentByTag(TAG) ?: return
            val dialogFragment = taggedFragment as? BottomSheetHomeMenuFragment ?: return
            dialogFragment.dismissAllowingStateLoss()
        }
    }
}
