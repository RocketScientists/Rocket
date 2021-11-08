/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.rocket.privately

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import dagger.Lazy
import org.mozilla.focus.BuildConfig
import org.mozilla.focus.R
import org.mozilla.focus.activity.BaseActivity
import org.mozilla.focus.activity.MainActivity
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.navigation.ScreenNavigator.BrowserScreen
import org.mozilla.focus.navigation.ScreenNavigator.HomeScreen
import org.mozilla.focus.navigation.ScreenNavigator.UrlInputScreen
import org.mozilla.focus.tabs.tabtray.TabTray
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.urlinput.UrlInputFragment
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.SafeIntent
import org.mozilla.focus.utils.ShortcutUtils
import org.mozilla.focus.utils.SupportUtils
import org.mozilla.rocket.browser.BrowserFragment
import org.mozilla.rocket.chrome.BottomBarViewModel
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.ChromeViewModel.OpenUrlAction
import org.mozilla.rocket.component.LaunchIntentDispatcher.LaunchMethod
import org.mozilla.rocket.component.PrivateSessionNotificationService
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getViewModel
import org.mozilla.rocket.download.data.DownloadsRepository.DownloadState.FileNotSupported
import org.mozilla.rocket.download.data.DownloadsRepository.DownloadState.GeneralError
import org.mozilla.rocket.download.data.DownloadsRepository.DownloadState.StorageUnavailable
import org.mozilla.rocket.download.data.DownloadsRepository.DownloadState.Success
import org.mozilla.rocket.landing.NavigationModel
import org.mozilla.rocket.landing.OrientationState
import org.mozilla.rocket.landing.PortraitComponent
import org.mozilla.rocket.landing.PortraitStateModel
import org.mozilla.rocket.menu.PrivateBrowserMenuDialog
import org.mozilla.rocket.privately.home.PrivateHomeFragment
import org.mozilla.rocket.tabs.TabsSessionProvider
import org.mozilla.rocket.theme.ThemeManager
import javax.inject.Inject

class PrivateModeActivity :
    BaseActivity(),
    ThemeManager.ThemeHost,
    ScreenNavigator.Provider,
    ScreenNavigator.HostActivity,
    TabsSessionProvider.SessionHost {

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var bottomBarViewModelCreator: Lazy<BottomBarViewModel>

    // TODO: apply new AC SessionManager
    private var sessionManagerLegacy: org.mozilla.rocket.tabs.SessionManager? = null

    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var tabViewProvider: PrivateTabViewProvider
    private lateinit var screenNavigator: ScreenNavigator
    private lateinit var snackBarContainer: View
    private lateinit var browserMenu: PrivateBrowserMenuDialog

    private val portraitStateModel = PortraitStateModel()

    private var themeManager: ThemeManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // we don't keep any state if user leave Private-mode
        appComponent().inject(this)
        super.onCreate(null)

        chromeViewModel = getViewModel(chromeViewModelCreator)
        chromeViewModel.isInPrivateMode = true
        val bottomBarViewModel = getViewModel(bottomBarViewModelCreator)
        bottomBarViewModel.isInPrivateMode = true

        tabViewProvider = PrivateTabViewProvider(this)
        screenNavigator = ScreenNavigator(this)

        themeManager = ThemeManager(this, ThemeManager.ThemeSet.Private)

        if (isSanitizeIntent(intent)) {
            sanitize()
            pushToBack()
            return
        }

        handleIntent(intent)

        setContentView(R.layout.activity_private_mode)

        setUpMenu()
        snackBarContainer = findViewById(R.id.container)
        makeStatusBarTransparent()

        screenNavigator.popToHomeScreen(false)
        observeChromeAction()

        monitorOrientationState()

        chromeViewModel.onRestoreTabCountCompleted()
        chromeViewModel.onTabCountChanged(0)
    }

    override fun onResume() {
        super.onResume()
        chromeViewModel.onSessionStarted()
    }

    override fun onPause() {
        super.onPause()
        chromeViewModel.onSessionEnded()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPrivateMode(false)
        sessionManagerLegacy?.destroy()
    }

    override fun applyLocale() {}
    private fun setUpMenu() {
        if (::browserMenu.isInitialized) {
            browserMenu.release()
        }
        browserMenu = PrivateBrowserMenuDialog(this, R.style.BottomSheetTheme).apply {
            setCanceledOnTouchOutside(true)
            setOnShowListener { portraitStateModel.request(PortraitComponent.BottomMenu) }
            setOnDismissListener { portraitStateModel.cancelRequest(PortraitComponent.BottomMenu) }
        }
    }

    private fun observeChromeAction() {
        chromeViewModel.showBrowserMenu.observe(this) { browserMenu.show() }
        chromeViewModel.showNewTab.observe(this) {
            screenNavigator.addHomeScreen(true)
        }

        chromeViewModel.showTabTray.observe(this) {
            TabTray.show(supportFragmentManager)
        }

        chromeViewModel.openUrl.observe(this) { action ->
            dismissUrlInput()
            startPrivateMode()
            screenNavigator.showBrowserScreen(action.url, action.withNewTab, action.isFromExternal)
        }

        chromeViewModel.showUrlInput.observe(this) { url ->
            if (!supportFragmentManager.isStateSaved) {
                screenNavigator.addUrlScreen(url)
            }
        }

        chromeViewModel.dismissUrlInput.observe(this) {
            dismissUrlInput()
        }

        // Reserve to handle more chrome actions for the bottom bar A/B testing
        chromeViewModel.pinShortcut.observe(this) { onAddToHomeClicked() }

        chromeViewModel.share.observe(this) {
            chromeViewModel.currentUrl.value?.let { url ->
                onShareClicked(url)
            }
        }

        chromeViewModel.togglePrivateMode.observe(this) {
            checkShortcutPromotion { pushToBack() }
        }

        chromeViewModel.dropCurrentPage.observe(this) {
            dropBrowserFragment()
        }

        chromeViewModel.downloadState.observe(this) { downloadState ->
            val msgResId = when (downloadState) {
                is GeneralError -> return@observe
                is StorageUnavailable -> R.string.message_storage_unavailable_cancel_download
                is FileNotSupported -> R.string.download_file_not_supported
                is Success ->
                    if (!downloadState.isStartFromContextMenu) {
                        R.string.download_started
                    } else {
                        return@observe
                    }
            }

            Toast.makeText(this, msgResId, Toast.LENGTH_LONG).show()
        }
    }

    private fun onAddToHomeClicked() {
        var sessionUrl = ""
        var sessionTitle = ""
        var sessionIcon: Bitmap? = null
        getSessionManager().focusSession?.let {
            sessionUrl = it.url ?: ""
            sessionIcon = it.favicon
            sessionTitle = it.title
        }

        // If we pin an invalid url as shortcut, the app will not function properly.
        // TODO: only enable the bottom menu item if the page is valid and loaded.
        if (!SupportUtils.isUrl(sessionUrl)) {
            return
        }

        val shortcut = Intent(Intent.ACTION_VIEW)
        // Use activity-alias name here so we can start whoever want to control launching behavior
        // Besides, RocketLauncherActivity not exported so using the alias-name is required.
        shortcut.setClassName(this, AppConstants.LAUNCHER_ACTIVITY_ALIAS)
        shortcut.data = Uri.parse(sessionUrl)
        shortcut.putExtra(LaunchMethod.EXTRA_BOOL_HOME_SCREEN_SHORTCUT.value, true)

        ShortcutUtils.requestPinShortcut(this, shortcut, sessionTitle, sessionUrl, sessionIcon)
    }

    private fun onShareClicked(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "text/plain"
        shareIntent.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)))
    }

    override fun onBackPressed() {
        if (supportFragmentManager.isStateSaved) {
            return
        }

        val handled = screenNavigator.visibleBrowserScreen?.onBackPressed() ?: false
        if (handled) {
            return
        }

        if (!this.screenNavigator.canGoBack()) {
            checkShortcutPromotion { maybeClosePrivateMode() }
            return
        }

        super.onBackPressed()
    }

    private fun maybeClosePrivateMode() {
        TelemetryWrapper.exitPrivateMode(TelemetryWrapper.Extra_Value.SYSTEM_BACK)
        if (chromeViewModel.tabCount.value == 0) {
            finish()
        } else {
            pushToBack()
        }
    }

    override fun getSessionManager(): org.mozilla.rocket.tabs.SessionManager {
        if (sessionManagerLegacy == null) {
            sessionManagerLegacy = org.mozilla.rocket.tabs.SessionManager(tabViewProvider)
        }

        // we just created it, it definitely not null
        return sessionManagerLegacy!!
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (isSanitizeIntent(intent)) {
            sanitize()
            return
        }

        handleIntent(intent)
        setIntent(intent)
    }

    private fun monitorOrientationState() {
        val orientationState = OrientationState(
            object : NavigationModel {
                override val navigationState: LiveData<ScreenNavigator.NavigationState>
                    get() = ScreenNavigator[this@PrivateModeActivity].navigationState
            },
            portraitStateModel
        )

        orientationState.observe(this) { orientation ->
            if (orientation != null) {
                requestedOrientation = orientation
            }
        }
    }

    private fun dropBrowserFragment() {
        stopPrivateMode(false)
        Toast.makeText(this, R.string.private_browsing_erase_done, Toast.LENGTH_LONG).show()
    }

    override fun getScreenNavigator(): ScreenNavigator = screenNavigator

    override fun getBrowserScreen(): BrowserScreen =
        supportFragmentManager.findFragmentById(R.id.browser) as BrowserFragment

    override fun createFirstRunScreen(): ScreenNavigator.FirstrunScreen {
        if (BuildConfig.DEBUG) {
            throw RuntimeException("PrivateModeActivity should never show first-run")
        }
        TODO("PrivateModeActivity should never show first-run")
    }

    override fun getFirstRunScreen(): ScreenNavigator.FirstrunScreen? {
        if (BuildConfig.DEBUG) {
            throw RuntimeException("PrivateModeActivity should never show first-run")
        }
        TODO("PrivateModeActivity should never show first-run")
    }

    override fun createHomeScreen(): HomeScreen {
        return PrivateHomeFragment.create()
    }

    override fun createUrlInputScreen(url: String?, parentFragmentTag: String): UrlInputScreen =
        UrlInputFragment.create(url, parentFragmentTag, allowSuggestion = false, privateMode = true)

    override fun getThemeManager(): ThemeManager? = themeManager

    override fun getTheme(): Resources.Theme {
        val theme = super.getTheme()

        //  Oppo with android 5.1 call getTheme before activity onCreate invoked.
        //  So themeManager is not initialized and cause NPE
        themeManager?.applyCurrentTheme(theme)

        return theme
    }

    private fun pushToBack() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME)
        startActivity(intent)
        overridePendingTransition(0, R.anim.pb_exit)
    }

    private fun dismissUrlInput() {
        screenNavigator.popUrlScreen()
    }

    private fun makeStatusBarTransparent() {
        val appended = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        // do not overwrite existing value
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or appended
    }

    private fun startPrivateMode() {
        PrivateSessionNotificationService.start(this)
    }

    private fun stopPrivateMode(removeTask: Boolean) {
        PrivateSessionNotificationService.stop(this)
        PrivateMode.getInstance(this).sanitize()
        tabViewProvider.purify(this)
        if (removeTask) {
            finishAndRemoveTask()
        }
    }

    private fun isSanitizeIntent(intent: Intent?): Boolean {
        return intent?.action == PrivateMode.INTENT_EXTRA_SANITIZE
    }

    private fun sanitize() {
        TelemetryWrapper.erasePrivateModeNotification()
        stopPrivateMode(true)
        Toast.makeText(this, R.string.private_browsing_erase_done, Toast.LENGTH_LONG).show()
    }

    private fun handleIntent(intent: Intent?) {
        val safeIntent = intent?.let { SafeIntent(it) } ?: return

        when (safeIntent.action) {
            Intent.ACTION_VIEW -> onReceiveViewIntent(safeIntent)
            Intent.ACTION_MAIN -> onReceiveMainIntent(safeIntent)
        }
    }

    private fun onReceiveViewIntent(intent: SafeIntent) {
        TelemetryWrapper.launchByPrivateModeShortcut(TelemetryWrapper.Extra_Value.EXTERNAL_APP)
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (fromHistory) {
            return
        }
        val url = intent.dataString ?: return
        val openUrlAction = OpenUrlAction(url, withNewTab = false, isFromExternal = true)
        chromeViewModel.openUrl.value = openUrlAction
    }

    private fun onReceiveMainIntent(intent: SafeIntent) {
        if (isIntentFromPrivateShortcut(intent)) {
            TelemetryWrapper.launchByPrivateModeShortcut(TelemetryWrapper.Extra_Value.LAUNCHER)
        }
    }

    private fun isIntentFromPrivateShortcut(intent: SafeIntent): Boolean {
        return intent.getBooleanExtra(LaunchMethod.EXTRA_BOOL_PRIVATE_MODE_SHORTCUT.value, false)
    }

    private fun checkShortcutPromotion(continuation: () -> Unit) {
        ViewModelProvider(this)
            .get(ShortcutViewModel::class.java)
            .interceptLeavingAndCheckShortcut(this)
            .observe(this) {
                continuation()
            }
    }

    companion object {
        fun getStartIntent(context: Context): Intent =
            Intent(context, PrivateModeActivity::class.java)
    }
}
