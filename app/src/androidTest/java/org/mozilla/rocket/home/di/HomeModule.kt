package org.mozilla.rocket.home.di

import android.content.Context
import dagger.Module
import dagger.Provides
import org.mockito.Mockito.spy
import org.mozilla.focus.tabs.tabtray.TabTrayViewModel
import org.mozilla.focus.utils.NewFeatureNotice
import org.mozilla.focus.utils.Settings
import org.mozilla.rocket.chrome.domain.ShouldShowNewMenuItemHintUseCase
import org.mozilla.rocket.home.HomeViewModel
import org.mozilla.rocket.home.data.ContentPrefRepo
import org.mozilla.rocket.home.domain.SetContentPrefUseCase
import org.mozilla.rocket.home.logoman.data.LogoManNotificationRepo
import org.mozilla.rocket.home.logoman.domain.DismissLogoManNotificationUseCase
import org.mozilla.rocket.home.logoman.domain.GetLogoManNotificationUseCase
import org.mozilla.rocket.home.logoman.domain.LastReadLogoManNotificationUseCase
import org.mozilla.rocket.home.onboarding.domain.SetSetDefaultBrowserOnboardingIsShownUseCase
import org.mozilla.rocket.home.onboarding.domain.SetThemeOnboardingIsShownUseCase
import org.mozilla.rocket.home.onboarding.domain.ShouldShowSetDefaultBrowserOnboardingUseCase
import org.mozilla.rocket.home.onboarding.domain.ShouldShowThemeOnboardingUseCase
import org.mozilla.rocket.home.topsites.data.PinSiteManager
import org.mozilla.rocket.home.topsites.data.SharedPreferencePinSiteDelegate
import org.mozilla.rocket.home.topsites.data.TopSitesRepo
import org.mozilla.rocket.home.topsites.domain.GetRecommendedSitesUseCase
import org.mozilla.rocket.home.topsites.domain.GetTopSitesUseCase
import org.mozilla.rocket.home.topsites.domain.IsTopSiteFullyPinnedUseCase
import org.mozilla.rocket.home.topsites.domain.PinTopSiteUseCase
import org.mozilla.rocket.home.topsites.domain.RemoveTopSiteUseCase
import org.mozilla.rocket.home.topsites.ui.AddNewTopSitesViewModel
import javax.inject.Singleton

@Module
object HomeModule {

    @JvmStatic
    @Provides
    fun provideHomeViewModel(
        settings: Settings,
        getTopSitesUseCase: GetTopSitesUseCase,
        isTopSiteFullyPinnedUseCase: IsTopSiteFullyPinnedUseCase,
        pinTopSiteUseCase: PinTopSiteUseCase,
        removeTopSiteUseCase: RemoveTopSiteUseCase,
        getLogoManNotificationUseCase: GetLogoManNotificationUseCase,
        lastReadLogoManNotificationUseCase: LastReadLogoManNotificationUseCase,
        dismissLogoManNotificationUseCase: DismissLogoManNotificationUseCase,
        shouldShowNewMenuItemHintUseCase: ShouldShowNewMenuItemHintUseCase,
        shouldShowThemeOnboardingUseCase: ShouldShowThemeOnboardingUseCase,
        setThemeOnboardingIsShownUseCase: SetThemeOnboardingIsShownUseCase,
        shouldShowSetDefaultBrowserOnboardingUseCase: ShouldShowSetDefaultBrowserOnboardingUseCase,
        setSetDefaultBrowserOnboardingIsShownUseCase: SetSetDefaultBrowserOnboardingIsShownUseCase
    ): HomeViewModel = HomeViewModel(
        settings,
        getTopSitesUseCase,
        isTopSiteFullyPinnedUseCase,
        pinTopSiteUseCase,
        removeTopSiteUseCase,
        getLogoManNotificationUseCase,
        lastReadLogoManNotificationUseCase,
        dismissLogoManNotificationUseCase,
        shouldShowNewMenuItemHintUseCase,
        shouldShowThemeOnboardingUseCase,
        setThemeOnboardingIsShownUseCase,
        shouldShowSetDefaultBrowserOnboardingUseCase,
        setSetDefaultBrowserOnboardingIsShownUseCase
    )

    @JvmStatic
    @Singleton
    @Provides
    fun provideGetTopSitesUseCase(
        topSitesRepo: TopSitesRepo,
        contentPrefRepo: ContentPrefRepo
    ): GetTopSitesUseCase = spy(GetTopSitesUseCase(topSitesRepo, contentPrefRepo))

    @JvmStatic
    @Singleton
    @Provides
    fun provideIsTopSiteFullyPinnedUseCase(topSitesRepo: TopSitesRepo): IsTopSiteFullyPinnedUseCase = IsTopSiteFullyPinnedUseCase(topSitesRepo)

    @JvmStatic
    @Singleton
    @Provides
    fun providePinTopSiteUseCase(topSitesRepo: TopSitesRepo): PinTopSiteUseCase = PinTopSiteUseCase(topSitesRepo)

    @JvmStatic
    @Singleton
    @Provides
    fun provideRemoveTopSiteUseCase(
        topSitesRepo: TopSitesRepo,
        contentPrefRepo: ContentPrefRepo
    ): RemoveTopSiteUseCase = RemoveTopSiteUseCase(topSitesRepo, contentPrefRepo)

    @JvmStatic
    @Singleton
    @Provides
    fun provideTopSitesRepo(
        appContext: Context,
        pinSiteManager: PinSiteManager
    ): TopSitesRepo = TopSitesRepo(appContext, pinSiteManager)

    @JvmStatic
    @Singleton
    @Provides
    fun providePinSiteManager(appContext: Context): PinSiteManager =
        PinSiteManager(SharedPreferencePinSiteDelegate(appContext))

    @JvmStatic
    @Singleton
    @Provides
    fun provideLogoManNotificationRepo(appContext: Context): LogoManNotificationRepo = LogoManNotificationRepo(appContext)

    @JvmStatic
    @Singleton
    @Provides
    fun provideGetLogoManNotificationUseCase(
        logoManNotificationRepo: LogoManNotificationRepo
    ): GetLogoManNotificationUseCase = GetLogoManNotificationUseCase(logoManNotificationRepo)

    @JvmStatic
    @Singleton
    @Provides
    fun provideLastReadLogoManNotificationUseCase(
        logoManNotificationRepo: LogoManNotificationRepo
    ): LastReadLogoManNotificationUseCase = LastReadLogoManNotificationUseCase(logoManNotificationRepo)

    @JvmStatic
    @Singleton
    @Provides
    fun provideDismissLogoManNotificationUseCase(
        logoManNotificationRepo: LogoManNotificationRepo
    ): DismissLogoManNotificationUseCase =
        DismissLogoManNotificationUseCase(logoManNotificationRepo)

    @JvmStatic
    @Provides
    fun provideTabTrayViewModel(): TabTrayViewModel =
        TabTrayViewModel()

    @JvmStatic
    @Singleton
    @Provides
    fun provideGetRecommendedSitesUseCase(topSitesRepo: TopSitesRepo): GetRecommendedSitesUseCase =
        GetRecommendedSitesUseCase(topSitesRepo)

    @JvmStatic
    @Provides
    fun provideAddNewTopSitesViewModel(
        getRecommendedSitesUseCase: GetRecommendedSitesUseCase,
        pinTopSiteUseCase: PinTopSiteUseCase
    ): AddNewTopSitesViewModel =
        AddNewTopSitesViewModel(getRecommendedSitesUseCase, pinTopSiteUseCase)

    @JvmStatic
    @Provides
    fun provideContentPrefRepo(appContext: Context): ContentPrefRepo = ContentPrefRepo(appContext)

    @JvmStatic
    @Provides
    fun provideSetContentPrefUseCase(contentPrefRepo: ContentPrefRepo): SetContentPrefUseCase =
        SetContentPrefUseCase(contentPrefRepo)

    @JvmStatic
    @Singleton
    @Provides
    fun provideShouldShowThemeOnboardingUseCase(newFeatureNotice: NewFeatureNotice): ShouldShowThemeOnboardingUseCase =
        ShouldShowThemeOnboardingUseCase(newFeatureNotice)

    @JvmStatic
    @Singleton
    @Provides
    fun provideSetThemeOnboardingIsShownUseCase(newFeatureNotice: NewFeatureNotice): SetThemeOnboardingIsShownUseCase =
        SetThemeOnboardingIsShownUseCase(newFeatureNotice)

    @JvmStatic
    @Singleton
    @Provides
    fun provideShouldShowSetDefaultBrowserOnboardingUseCase(newFeatureNotice: NewFeatureNotice): ShouldShowSetDefaultBrowserOnboardingUseCase =
        ShouldShowSetDefaultBrowserOnboardingUseCase(newFeatureNotice)

    @JvmStatic
    @Singleton
    @Provides
    fun provideSetSetDefaultBrowserOnboardingIsShownUseCase(newFeatureNotice: NewFeatureNotice): SetSetDefaultBrowserOnboardingIsShownUseCase =
        SetSetDefaultBrowserOnboardingIsShownUseCase(newFeatureNotice)
}
