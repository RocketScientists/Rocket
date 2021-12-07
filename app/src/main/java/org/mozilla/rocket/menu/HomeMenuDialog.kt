package org.mozilla.rocket.menu

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
import org.mozilla.rocket.extension.toFragmentActivity
import org.mozilla.rocket.nightmode.AdjustBrightnessDialog
import org.mozilla.rocket.shopping.search.ui.ShoppingSearchActivity
import org.mozilla.rocket.widget.LifecycleBottomSheetDialog
import javax.inject.Inject

class HomeMenuDialog : LifecycleBottomSheetDialog {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var menuViewModelCreator: Lazy<MenuViewModel>

    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var menuViewModel: MenuViewModel

    private lateinit var binding: BottomSheetHomeMenuBinding

    private val uiHandler = Handler(Looper.getMainLooper())

    constructor(context: Context) : super(context)
    constructor(context: Context, @StyleRes theme: Int) : super(context, theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent().inject(this)
        super.onCreate(savedInstanceState)
        context.toFragmentActivity().lifecycle.addObserver(this)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        menuViewModel = getActivityViewModel(menuViewModelCreator)

        initLayout()
        observeChromeAction()
        setCancelable(false)
        setCanceledOnTouchOutside(true)
    }

    override fun dismiss() {
        if (::binding.isInitialized) {
            resetStates()
        }
        super.dismiss()
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun show() {
        super.show()
        BottomSheetBehavior
            .from(binding.bottomSheet)
            // by default, BottomSheet is showing half part, it is Collapsed
            .also { it.state = BottomSheetBehavior.STATE_COLLAPSED }
    }

    private fun resetStates() {
        hideNewItemHint()
    }

    private fun initLayout() {
        binding = BottomSheetHomeMenuBinding.inflate(layoutInflater, null, false)
        initMenuTabs()
        initMenuItems()
        setContentView(binding.root)
        val helperBinding = ScrollableBottomSheetHelper.Binding(
            binding.root,
            binding.container,
            binding.bottomSheet
        )

        ScrollableBottomSheetHelper.makeViewScrollable(context, helperBinding) {
            dismiss()
        }
    }

    private fun initMenuTabs() {
        binding.contentLayout.apply {
            chromeViewModel.hasUnreadScreenshot.observe(this@HomeMenuDialog) {
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
            chromeViewModel.isNightMode.observe(this@HomeMenuDialog) { nightModeSettings ->
                binding.nightModeSwitch.isChecked = nightModeSettings.isEnabled
            }
            menuViewModel.isHomeScreenShoppingSearchEnabled.observe(this@HomeMenuDialog) {
                binding.btnPrivateBrowsing.isVisible = !it
                binding.menuSmartShoppingSearch.isVisible = it
            }
            chromeViewModel.isPrivateBrowsingActive.observe(this@HomeMenuDialog) {
                // TODO: how to re-enable this?
                // we removed this image, and use `drawableStart` instead
                //binding.imgPrivateMode.isActivated = it
            }
            menuViewModel.shouldShowNewMenuItemHint.observe(this@HomeMenuDialog) {
                if (it) {
                    showNewItemHint()
                    menuViewModel.onNewMenuItemDisplayed()
                }
            }

            binding.btnPrivateBrowsing.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.togglePrivateMode.call()
                    TelemetryWrapper.togglePrivateMode(true)
                }
            }
            binding.menuSmartShoppingSearch.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    showShoppingSearch()
                }
            }
            binding.menuNightMode.setOnClickListener {
                chromeViewModel.adjustNightMode()
            }
            binding.nightModeSwitch.setOnCheckedChangeListener { _, isChecked ->
                val needToUpdate =
                    isChecked != (chromeViewModel.isNightMode.value?.isEnabled == true)
                if (needToUpdate) {
                    chromeViewModel.onNightModeToggled()
                }
            }
            binding.menuAddTopSites.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.onAddNewTopSiteMenuClicked()
                    TelemetryWrapper.clickMenuAddTopsite()
                }
            }
            binding.menuThemes.setOnClickListener {
                postDelayClickEvent {
                    cancel()
                    chromeViewModel.onThemeSettingMenuClicked()
                    TelemetryWrapper.clickMenuTheme()
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

    private fun showNewItemHint() {
        binding.addTopSitesRedDot.visibility = View.VISIBLE
        binding.themesRedDot.visibility = View.VISIBLE
    }

    private fun hideNewItemHint() {
        binding.addTopSitesRedDot.visibility = View.INVISIBLE
        binding.themesRedDot.visibility = View.INVISIBLE
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

    private fun showShoppingSearch() {
        context.startActivity(ShoppingSearchActivity.getStartIntent(context))
    }

    /**
     * Post delay click event to wait the clicking feedback shows
     */
    private fun postDelayClickEvent(action: () -> Unit) {
        uiHandler.postDelayed(
            {
                action()
            },
            150
        )
    }
}
