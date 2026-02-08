package org.mozilla.rocket.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.Settings
import org.mozilla.rocket.chrome.domain.ShouldShowNewMenuItemHintUseCase
import org.mozilla.rocket.download.SingleLiveEvent
import org.mozilla.rocket.extension.first
import org.mozilla.rocket.extension.map
import org.mozilla.rocket.home.logoman.domain.DismissLogoManNotificationUseCase
import org.mozilla.rocket.home.logoman.domain.GetLogoManNotificationUseCase
import org.mozilla.rocket.home.logoman.domain.LastReadLogoManNotificationUseCase
import org.mozilla.rocket.home.logoman.ui.LogoManNotification.Notification
import org.mozilla.rocket.home.onboarding.domain.SetSetDefaultBrowserOnboardingIsShownUseCase
import org.mozilla.rocket.home.onboarding.domain.SetThemeOnboardingIsShownUseCase
import org.mozilla.rocket.home.onboarding.domain.ShouldShowSetDefaultBrowserOnboardingUseCase
import org.mozilla.rocket.home.onboarding.domain.ShouldShowThemeOnboardingUseCase
import org.mozilla.rocket.home.topsites.domain.GetTopSitesUseCase
import org.mozilla.rocket.home.topsites.domain.IsTopSiteFullyPinnedUseCase
import org.mozilla.rocket.home.topsites.domain.PinTopSiteUseCase
import org.mozilla.rocket.home.topsites.domain.RemoveTopSiteUseCase
import org.mozilla.rocket.home.topsites.ui.Site
import org.mozilla.rocket.home.topsites.ui.SitePage
import org.mozilla.rocket.home.topsites.ui.TopSiteClickListener
import org.mozilla.rocket.theme.ThemeManager
import org.mozilla.rocket.util.ToastMessage

class HomeViewModel(
    private val settings: Settings,
    private val getTopSitesUseCase: GetTopSitesUseCase,
    private val isTopSiteFullyPinnedUseCase: IsTopSiteFullyPinnedUseCase,
    private val pinTopSiteUseCase: PinTopSiteUseCase,
    private val removeTopSiteUseCase: RemoveTopSiteUseCase,
    private val getLogoManNotificationUseCase: GetLogoManNotificationUseCase,
    private val lastReadLogoManNotificationUseCase: LastReadLogoManNotificationUseCase,
    private val dismissLogoManNotificationUseCase: DismissLogoManNotificationUseCase,
    shouldShowNewMenuItemHintUseCase: ShouldShowNewMenuItemHintUseCase,
    shouldShowThemeOnboardingUseCase: ShouldShowThemeOnboardingUseCase,
    setThemeOnboardingIsShownUseCase: SetThemeOnboardingIsShownUseCase,
    private val shouldShowSetDefaultBrowserOnboardingUseCase: ShouldShowSetDefaultBrowserOnboardingUseCase,
    private val setSetDefaultBrowserOnboardingIsShownUseCase: SetSetDefaultBrowserOnboardingIsShownUseCase
) : ViewModel(), TopSiteClickListener {

    val sitePages = MutableLiveData<List<SitePage>>()
    val topSitesPageIndex = MutableLiveData<Int>()
    val logoManNotification = MediatorLiveData<StateNotification?>()

    val shouldShowNewMenuItemHint: LiveData<Boolean> = shouldShowNewMenuItemHintUseCase()

    val toggleBackgroundColor = SingleLiveEvent<Unit>()
    val resetBackgroundColor = SingleLiveEvent<Unit>()
    val openPrivateMode = SingleLiveEvent<Unit>()
    val openBrowser = SingleLiveEvent<String>()
    val showTopSiteMenu = SingleLiveEvent<ShowTopSiteMenuData>()
    val showAddTopSiteMenu = SingleLiveEvent<Unit>()
    val showToast = SingleLiveEvent<ToastMessage>()
    val hideLogoManNotification = SingleLiveEvent<Unit>()
    val executeUriAction = SingleLiveEvent<String>()
    val showKeyboard = SingleLiveEvent<Unit>()
    val openAddNewTopSitesPage = SingleLiveEvent<Unit>()
    val addNewTopSiteSuccess = SingleLiveEvent<Int>()
    val addNewTopSiteFullyPinned = SingleLiveEvent<Unit>()
    val addExistingTopSite = SingleLiveEvent<Int>()
    val homeBackgroundColorThemeClicked = SingleLiveEvent<ThemeManager.ThemeSet>()
    val showThemeSetting = SingleLiveEvent<Unit>()
    val showSetAsDefaultBrowserOnboarding = SingleLiveEvent<Unit>()
    val tryToSetDefaultBrowser = SingleLiveEvent<Unit>()

    private var logoManClickAction: GetLogoManNotificationUseCase.LogoManAction? = null
    private var logoManType: String? = null
    private var hasLoggedShowLogoman = false
    private var pinTopSiteResult: PinTopSiteUseCase.PinTopSiteResult? = null

    init {
        initLogoManData()

        if (shouldShowThemeOnboardingUseCase()) {
            setThemeOnboardingIsShownUseCase()
            showThemeSetting.call()
            TelemetryWrapper.showThemeContextualHint()
        }
    }

    private fun initLogoManData() {
        logoManNotification.addSource(
            getLogoManNotificationUseCase().first()
                .map {
                    logoManClickAction = it?.action
                    logoManType = it?.type
                    it?.run { StateNotification(it.toUiModel(), true) }
                }
        ) {
            logoManNotification.value = it
        }
        val lastReadIdObserver = Observer<String> { lastReadId ->
            val showingNotification = logoManNotification.value
            if (showingNotification != null && showingNotification.notification.id == lastReadId) {
                hideLogoManNotification.call()
                logoManNotification.value = null
            }
        }
        logoManNotification.addSource(lastReadLogoManNotificationUseCase(), lastReadIdObserver)
    }

    private fun updateTopSitesData() = viewModelScope.launch {
        val topSiteList = getTopSitesUseCase()
        sitePages.value = if (topSiteList.isNotEmpty()) {
            topSiteList.addDummyTopSites().toSitePages().also { sitePages ->
                val sitePosition = when (val result = pinTopSiteResult) {
                    is PinTopSiteUseCase.PinTopSiteResult.Success -> result.position
                    is PinTopSiteUseCase.PinTopSiteResult.Existing -> result.position
                    else -> -1
                }
                if (sitePosition != -1) {
                    val sitePage = sitePosition / TOP_SITES_PER_PAGE
                    val siteInPageIndex = sitePosition % TOP_SITES_PER_PAGE
                    if (sitePage < sitePages.size && siteInPageIndex < sitePages[sitePage].sites.size) {
                        when (val topSite = sitePages[sitePage].sites[siteInPageIndex]) {
                            is Site.UrlSite -> topSite.highlight = true
                            // FIXME: find a proper way to handle this
                            Site.DummySite,
                            Site.EmptyHintSite -> Unit
                        }
                    }
                }
                pinTopSiteResult = null
            }
        } else {
            listOf(Site.EmptyHintSite).addDummyTopSites().toSitePages()
        }
    }

    private fun List<Site>.toSitePages(): List<SitePage> = chunked(TOP_SITES_PER_PAGE)
        .take(TOP_SITES_MAX_PAGE_SIZE)
        .map { SitePage(it) }

    private fun List<Site>.addDummyTopSites(): List<Site> {
        val siteListWithDummySite = this.toMutableList()
        val dummySiteSize = TOP_SITES_PER_PAGE * TOP_SITES_MAX_PAGE_SIZE - this.size
        for (i in 0 until dummySiteSize) {
            siteListWithDummySite.add(Site.DummySite)
        }
        return siteListWithDummySite
    }

    fun onPageForeground() {
        val logoManNotification = logoManNotification.value
        if (!hasLoggedShowLogoman && logoManNotification != null) {
            hasLoggedShowLogoman = true
            TelemetryWrapper.showLogoman(
                logoManType,
                logoManClickAction?.getLink(),
                logoManNotification.notification.id
            )
        }
        TelemetryWrapper.showHome()
        updateTopSitesData()
    }

    fun onPageBackground() {
        hasLoggedShowLogoman = false
    }

    fun onTopSitesPagePositionChanged(position: Int) {
        topSitesPageIndex.value = position
    }

    fun onBackgroundViewDoubleTap(): Boolean {
        // Not allowed double tap to switch theme when dark theme is on
        if (settings.isDarkThemeEnable) return false

        toggleBackgroundColor.call()
        return true
    }

    fun onBackgroundViewLongPress() {
        // Not allowed long press to reset theme when dark theme is on
        if (settings.isDarkThemeEnable) return

        resetBackgroundColor.call()
    }

    fun onThemeClicked(isDarkTheme: Boolean, theme: ThemeManager.ThemeSet) {
        settings.setDarkTheme(isDarkTheme)
        // No need to update background color theme when in dark theme
        if (isDarkTheme) {
            return
        }
        homeBackgroundColorThemeClicked.value = theme
    }

    fun onPrivateModeButtonClicked() {
        openPrivateMode.call()
        TelemetryWrapper.togglePrivateMode(true)
    }

    override fun onTopSiteClicked(site: Site, position: Int) {
        when (site) {
            is Site.UrlSite -> {
                openBrowser.value = site.url
                val allowToLogTitle = when (site) {
                    is Site.UrlSite.FixedSite -> true
                    is Site.UrlSite.RemovableSite -> site.isDefault
                }
                val title = if (allowToLogTitle) site.title else ""
                val pageIndex = requireNotNull(topSitesPageIndex.value)
                val topSitePosition = position + pageIndex * TOP_SITES_PER_PAGE
                val isPinned = if (site is Site.UrlSite.RemovableSite) {
                    site.isPinned
                } else {
                    false
                }
                TelemetryWrapper.clickTopSiteOn(topSitePosition, title, allowToLogTitle, isPinned)
            }
            is Site.EmptyHintSite -> {
                openAddNewTopSitesPage()
                TelemetryWrapper.addTopSite(TelemetryWrapper.Extra_Value.EMPTY_HINT)
            }
            // FIXME: find a proper way to handle this
            Site.DummySite -> Unit
        }
    }

    override fun onTopSiteLongClicked(site: Site, position: Int): Boolean =
        if (site is Site.UrlSite.RemovableSite || site is Site.DummySite) {
            val pageIndex = requireNotNull(topSitesPageIndex.value)
            val topSitePosition = position + pageIndex * TOP_SITES_PER_PAGE
            when (site) {
                is Site.UrlSite.RemovableSite ->
                    showTopSiteMenu.value =
                        ShowTopSiteMenuData(site, topSitePosition)
                is Site.DummySite -> showAddTopSiteMenu.call()
                // FIXME: find a proper way to handle this
                Site.EmptyHintSite,
                is Site.UrlSite.FixedSite -> Unit
            }
            true
        } else {
            false
        }

    fun onPinTopSiteClicked(site: Site, position: Int) = viewModelScope.launch {
        when (site) {
            is Site.UrlSite -> {
                pinTopSiteUseCase(site)
                updateTopSitesData()
                val allowToLogTitle = if (site is Site.UrlSite.RemovableSite) {
                    site.isDefault
                } else {
                    false
                }
                val title = if (allowToLogTitle) site.title else ""
                TelemetryWrapper.pinTopSite(title, position, allowToLogTitle)
            }
            // FIXME: find a proper way to handle this
            Site.DummySite,
            Site.EmptyHintSite -> Unit
        }
    }

    fun onRemoveTopSiteClicked(site: Site, position: Int) = viewModelScope.launch {
        when (site) {
            is Site.UrlSite.RemovableSite -> {
                removeTopSiteUseCase(site)
                updateTopSitesData()
                val allowToLogTitle = site.isDefault
                val title = if (allowToLogTitle) site.title else ""
                TelemetryWrapper.removeTopSite(site.isDefault, position, title, site.isPinned)
            }
            // FIXME: find a proper way to handle this
            Site.DummySite,
            Site.EmptyHintSite,
            is Site.UrlSite.FixedSite -> Unit
        }
    }

    fun onAddNewTopSiteResult(pinTopSiteResult: PinTopSiteUseCase.PinTopSiteResult) {
        this.pinTopSiteResult = pinTopSiteResult
        when (pinTopSiteResult) {
            is PinTopSiteUseCase.PinTopSiteResult.Success -> {
                addNewTopSiteSuccess.value = pinTopSiteResult.position / TOP_SITES_PER_PAGE
            }
            is PinTopSiteUseCase.PinTopSiteResult.Existing -> {
                addExistingTopSite.value = pinTopSiteResult.position / TOP_SITES_PER_PAGE
            }
            // FIXME: find a proper way to handle this
            PinTopSiteUseCase.PinTopSiteResult.FullyPinned -> Unit
        }
    }

    fun onAddTopSiteContextMenuClicked() {
        openAddNewTopSitesPage()
        TelemetryWrapper.addTopSite(TelemetryWrapper.Extra_Value.CONTEXT_MENU)
    }

    private fun openAddNewTopSitesPage() = viewModelScope.launch {
        val fullyPinned = isTopSiteFullyPinnedUseCase()
        if (fullyPinned) {
            addNewTopSiteFullyPinned.call()
        } else {
            openAddNewTopSitesPage.call()
        }
    }

    fun onAddTopSiteMenuClicked() {
        openAddNewTopSitesPage()
    }

    fun onAddMoreTopSiteSnackBarClicked() {
        openAddNewTopSitesPage()
        TelemetryWrapper.clickAddTopSiteFromSnackBar()
    }

    fun onThemeSettingMenuClicked() {
        showThemeSetting.call()
    }

    fun onExitThemeSetting(selectedTheme: String) {
        if (shouldShowSetDefaultBrowserOnboardingUseCase()) {
            setSetDefaultBrowserOnboardingIsShownUseCase()
            showSetAsDefaultBrowserOnboarding.call()
            TelemetryWrapper.showGoSetDefaultMessage()
        }
        TelemetryWrapper.clickThemeContextualHint(selectedTheme)
    }

    fun onSetAsDefaultBrowserClicked() {
        tryToSetDefaultBrowser.call()
        TelemetryWrapper.clickGoSetDefaultMessage(TelemetryWrapper.Extra_Value.OK)
    }

    fun onCancelSetAsDefaultBrowserClicked() {
        TelemetryWrapper.clickGoSetDefaultMessage(TelemetryWrapper.Extra_Value.LATER)
    }

    fun onClearBrowsingHistory() {
        updateTopSitesData()
    }

    fun onLogoManShown() {
        // Make it only animate once. Remove this when Home Screen doesn't recreate whenever goes back from browser
        val logoManNotification = logoManNotification.value
        logoManNotification?.animate = false
        if (!hasLoggedShowLogoman) {
            hasLoggedShowLogoman = true
            TelemetryWrapper.showLogoman(
                logoManType,
                logoManClickAction?.getLink(),
                logoManNotification?.notification?.id
            )
        }
    }

    fun onLogoManNotificationClicked() {
        logoManClickAction?.let {
            executeLogomanAction(it)
            val notification = logoManNotification.value?.notification
            if (notification != null && notification !is Notification.MissionNotification) {
                // no need to call dismissLogoManNotificationUseCase since it will also be done when mission detail page get opened
                dismissLogoManNotificationUseCase(notification)
            }
        }
        TelemetryWrapper.clickLogoman(
            logoManType,
            logoManClickAction?.getLink(),
            logoManNotification.value?.notification?.id
        )
    }

    private fun executeLogomanAction(logomanAction: GetLogoManNotificationUseCase.LogoManAction) {
        when (logomanAction) {
            is GetLogoManNotificationUseCase.LogoManAction.UriAction -> {
                // workaround to handle open url action then be able to back to home page when clicking back key
                if (logomanAction.action.startsWith("https://") or logomanAction.action.startsWith("http://")) {
                    openBrowser.value = logomanAction.action
                } else {
                    executeUriAction.value = logomanAction.action
                }
            }
        }
    }

    fun onLogoManDismissed() {
        logoManNotification.value?.notification?.let {
            logoManNotification.value = null
            dismissLogoManNotificationUseCase(it)
            TelemetryWrapper.swipeLogoman(logoManType, logoManClickAction?.getLink(), it.id)
        }
    }

    fun onNewTabButtonClicked() {
        showKeyboard.call()
    }

    data class ShowTopSiteMenuData(
        val site: Site,
        val position: Int
    )

    // Remove this when Home Screen doesn't recreate whenever goes back from browser
    data class StateNotification(
        val notification: Notification,
        var animate: Boolean
    )

    companion object {
        private const val TOP_SITES_MAX_PAGE_SIZE = 2
        private const val TOP_SITES_PER_PAGE = 8
    }
}

private fun GetLogoManNotificationUseCase.Notification.toUiModel() = when (this) {
    is GetLogoManNotificationUseCase.Notification.RemoteNotification -> Notification.RemoteNotification(
        id,
        title,
        subtitle,
        imageUrl
    )
}
