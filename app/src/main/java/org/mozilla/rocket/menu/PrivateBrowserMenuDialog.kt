package org.mozilla.rocket.menu

import android.content.Context
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import dagger.Lazy
import mozilla.components.concept.engine.EngineSession
import org.mozilla.focus.FocusApplication
import org.mozilla.focus.R
import org.mozilla.focus.databinding.BottomSheetPrivateBrowserMenuBinding
import org.mozilla.focus.web.BrowsingSession
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.PrivateMenuViewModel
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType
import org.mozilla.rocket.content.app
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.extension.nonNullObserve
import org.mozilla.rocket.extension.switchFrom
import org.mozilla.rocket.widget.LifecycleBottomSheetDialog
import javax.inject.Inject

class PrivateBrowserMenuDialog : LifecycleBottomSheetDialog {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var menuViewModelCreator: Lazy<PrivateMenuViewModel>

    private lateinit var menuViewModel: PrivateMenuViewModel
    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var bottomBarItemAdapter: BottomBarItemAdapter

    private lateinit var binding: BottomSheetPrivateBrowserMenuBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private val counterColorEnabled = ContextCompat.getColor(context, R.color.palettePurple100)
    private val counterColorDisabled = ContextCompat.getColor(context, R.color.paletteWhite100)

    constructor(context: Context) : super(context)
    constructor(context: Context, @StyleRes theme: Int) : super(context, theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent().inject(this)
        super.onCreate(savedInstanceState)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        menuViewModel = getActivityViewModel(menuViewModelCreator)

        initLayout()
        setCancelable(false)
        setCanceledOnTouchOutside(true)
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun initLayout() {
        binding = BottomSheetPrivateBrowserMenuBinding.inflate(layoutInflater, null, false)
        binding.contentLayout.apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = resources.getDimension(R.dimen.menu_corner_radius)
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }
        setContentView(binding.root)

        initBottomBar()
        initTrackerView()
    }

    private fun initBottomBar() {
        val bottomBar = binding.menuBottomBar
        bottomBar.setOnItemClickListener { type, position ->
            cancel()
            when (type) {
                ItemType.MENU -> chromeViewModel.showBrowserMenu.call()
                ItemType.NEXT -> chromeViewModel.goNext.call()
                ItemType.BACK -> chromeViewModel.goBack.call()
                ItemType.REFRESH -> chromeViewModel.refreshOrStop.call()
                ItemType.SHARE -> chromeViewModel.share.call()
                ItemType.SEARCH -> chromeViewModel.showUrlInput.call()
                ItemType.TAB_COUNTER,
                ItemType.HOME,
                ItemType.CAPTURE,
                ItemType.PIN_SHORTCUT,
                ItemType.BOOKMARK,
                ItemType.PRIVATE_HOME,
                ItemType.DELETE,
                ItemType.TRACKER,
                ItemType.SHOPPING_SEARCH ->
                    throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
            } // move Telemetry to ScreenCaptureTask doInBackground() cause we need to init category first.
        }
        bottomBarItemAdapter =
            BottomBarItemAdapter(bottomBar, BottomBarItemAdapter.Theme.PrivateMode)
        menuViewModel.bottomItems.nonNullObserve(this) { bottomItems ->
            bottomBarItemAdapter.setItems(bottomItems)
        }

        chromeViewModel.canGoForward.switchFrom(menuViewModel.bottomItems).observe(this) {
            bottomBarItemAdapter.setCanGoForward(it == true)
        }
        chromeViewModel.canGoBack.switchFrom(menuViewModel.bottomItems).observe(this) {
            bottomBarItemAdapter.setCanGoBack(it == true)
        }
    }

    private fun initTrackerView() {
        binding.trackerContentView.setBackgroundDrawable(
            ContextCompat.getDrawable(
                context,
                R.drawable.background_tracker_popup
            )
        )

        val switchView = binding.trackerSwitch
        DrawableCompat.setTintList(
            switchView.thumbDrawable,
            ContextCompat.getColorStateList(context, R.color.switch_thumb_dark)
        )
        DrawableCompat.setTintList(
            switchView.trackDrawable,
            ContextCompat.getColorStateList(context, R.color.switch_track_dark)
        )

        val counterBkg = DrawableCompat.wrap(binding.trackerCountContainer.background).mutate()
        binding.trackerCountContainer.background = counterBkg

        val counterView = binding.trackerCount
        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                counterView.text = "0"
                DrawableCompat.setTint(counterBkg, counterColorEnabled)
            } else {
                binding.trackerCount.text = COUNT_DISABLED
                DrawableCompat.setTint(counterBkg, counterColorDisabled)
            }

            setTurboModeSettingInPrivateMode(isChecked)
        }

        val isTurboModeEnabled = isTurboModeEnabledInPrivateMode(context)
        binding.trackerSwitch.isChecked = isTurboModeEnabled
        BrowsingSession.getInstance().blockedTrackerCount.observe(this) { setBlockedCount(it) }
    }

    private fun setBlockedCount(count: Int?) {
        if (!binding.trackerSwitch.isChecked) {
            return
        }

        val countStr = count?.toString() ?: return
        binding.trackerCount.text = if (countStr.length <= MAX_NUMBER_OF_DIGITS) {
            countStr
        } else {
            COUNT_INFINITY
        }
    }

    private fun isTurboModeEnabledInPrivateMode(context: Context): Boolean {
        val appContext = context.applicationContext as? FocusApplication ?: return false
        return appContext.settings.privateBrowsingSettings.shouldUseTurboMode()
    }

    private fun setTurboModeSettingInPrivateMode(isEnabled: Boolean) {
        chromeViewModel.isPrivateTurboModeEnabled.value = isEnabled
        val policy = if (isEnabled) {
            EngineSession.TrackingProtectionPolicy.all()
        } else {
            null
        }
        app().settings.privateBrowsingSettings.setTurboMode(isEnabled)
        app().engineSettings.trackingProtectionPolicy = policy
        // TODO: move to Session.Observer.onTrackerBlockingEnabledChanged
        // for now the callback has a bug in version 0.52.0
        bottomBarItemAdapter.setTrackerSwitch(isEnabled)
    }

    companion object {
        private const val COUNT_INFINITY = "∞"
        private const val COUNT_DISABLED = "-"
        private const val MAX_NUMBER_OF_DIGITS = 2
    }
}
