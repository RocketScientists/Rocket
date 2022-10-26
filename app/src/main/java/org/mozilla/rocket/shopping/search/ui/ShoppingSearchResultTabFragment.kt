package org.mozilla.rocket.shopping.search.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.ViewCompat.getLayoutDirection
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager.widget.ViewPager
import com.google.android.material.animation.AnimationUtils
import com.google.android.material.tabs.TabLayout
import dagger.Lazy
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentShoppingSearchResultTabBinding
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.widget.BackKeyHandleable
import org.mozilla.rocket.chrome.BottomBarItemAdapter
import org.mozilla.rocket.chrome.ChromeViewModel
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.common.ui.ContentTabFragment
import org.mozilla.rocket.content.common.ui.ContentTabHelper
import org.mozilla.rocket.content.common.ui.ContentTabViewContract
import org.mozilla.rocket.content.common.ui.TabSwipeTelemetryViewModel
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.content.getViewModel
import org.mozilla.rocket.extension.nonNullObserve
import org.mozilla.rocket.extension.switchFrom
import org.mozilla.rocket.shopping.search.data.ShoppingSearchMode
import org.mozilla.rocket.shopping.search.ui.ShoppingSearchTabsAdapter.TabItem
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.TabsSessionProvider
import org.mozilla.rocket.tabs.utils.TabUtil
import javax.inject.Inject

class ShoppingSearchResultTabFragment : Fragment(), ContentTabViewContract, BackKeyHandleable {

    var binding: FragmentShoppingSearchResultTabBinding? = null

    @Inject
    lateinit var viewModelCreator: Lazy<ShoppingSearchResultViewModel>

    @Inject
    lateinit var chromeViewModelCreator: Lazy<ChromeViewModel>

    @Inject
    lateinit var bottomBarViewModelCreator: Lazy<ShoppingSearchBottomBarViewModel>

    @Inject
    lateinit var telemetryViewModelCreator: Lazy<TabSwipeTelemetryViewModel>

    private lateinit var shoppingSearchResultViewModel: ShoppingSearchResultViewModel
    private lateinit var chromeViewModel: ChromeViewModel
    private lateinit var telemetryViewModel: TabSwipeTelemetryViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var contentTabHelper: ContentTabHelper
    private lateinit var contentTabObserver: ContentTabHelper.Observer
    private lateinit var bottomBarItemAdapter: BottomBarItemAdapter

    private val scrollAnimator: ValueAnimator by lazy {
        ValueAnimator().apply {
            interpolator = AnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR
            duration = ANIMATION_DURATION
            addUpdateListener { animator ->
                binding?.tabLayoutScrollView?.scrollTo(
                    animator.animatedValue as Int,
                    0
                )
            }
        }
    }

    private val safeArgs: ShoppingSearchResultTabFragmentArgs by navArgs()
    private val searchKeyword by lazy { safeArgs.searchKeyword }
    private val tabItems = arrayListOf<TabItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent().inject(this)
        super.onCreate(savedInstanceState)
        shoppingSearchResultViewModel = getViewModel(viewModelCreator)
        chromeViewModel = getActivityViewModel(chromeViewModelCreator)
        telemetryViewModel = getActivityViewModel(telemetryViewModelCreator)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentShoppingSearchResultTabBinding.inflate(inflater, container, false).also {
        this.binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = this.binding ?: return

        setupBottomBar(binding)

        binding.appbar.setOnApplyWindowInsetsListener { v, insets ->
            (v.layoutParams as ViewGroup.MarginLayoutParams).topMargin = insets.systemWindowInsetTop
            insets
        }
        binding.viewPager.setOnApplyWindowInsetsListener { v, insets ->
            if (insets.systemWindowInsetBottom == 0) {
                v.setPadding(
                    0,
                    0,
                    0,
                    resources.getDimensionPixelSize(R.dimen.fixed_menu_height) + insets.systemWindowInsetTop
                )
            } else {
                v.setPadding(0, 0, 0, insets.systemWindowInsetTop)
            }
            insets
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val binding = this.binding ?: return
        initUrlBar(binding)
        initViewPager(binding)
        initTabLayout(binding)

        contentTabHelper = ContentTabHelper(this)
        contentTabHelper.initPermissionHandler()
        contentTabObserver = contentTabHelper.getObserver()
        sessionManager = TabsSessionProvider.getOrThrow(activity)
        sessionManager.register(contentTabObserver)
        sessionManager.focusSession?.register(contentTabObserver)

        observeChromeAction()

        shoppingSearchResultViewModel.search(searchKeyword)

        ShoppingSearchMode.getInstance(requireContext()).saveKeyword(searchKeyword)

        observeAction()
    }

    private fun observeAction() {
        shoppingSearchResultViewModel.goBackToInputPage.observe(viewLifecycleOwner) {
            goBackToSearchInputPage()
        }
    }

    override fun onResume() {
        super.onResume()
        sessionManager.resume()
        binding?.appbar?.requestApplyInsets()
    }

    override fun onPause() {
        super.onPause()
        sessionManager.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        this.binding = null
        sessionManager.focusSession?.unregister(contentTabObserver)
        sessionManager.unregister(contentTabObserver)
    }

    override fun getHostActivity() = activity as AppCompatActivity

    override fun getCurrentSession() = sessionManager.focusSession

    override fun getChromeViewModel() = chromeViewModel

    override fun getSiteIdentity(): ImageView? {
        // TODO: we should use ViewBinding to ensure we got the correct instance and type
        // It is a long trip looking for the target view, across Custom View and include-xml
        val binding = this.binding ?: return null
        // layout_collapsing_url_bar.xml
        val collapsingUrlBar: CollapsingUrlBar = binding.urlBar
        // toolbar.xml
        val toolBar: View = collapsingUrlBar.findViewById(R.id.toolbar) ?: return null
        return toolBar.findViewById(R.id.site_identity)
    }

    override fun getDisplayUrlView(): TextView? {
        // TODO: we should use ViewBinding to ensure we got the correct instance and type
        // It is a long trip looking for the target view, across Custom View and include-xml
        val binding = this.binding ?: return null
        // layout_collapsing_url_bar.xml
        val collapsingUrlBar: CollapsingUrlBar = binding.urlBar
        // toolbar.xml
        val toolBar: View = collapsingUrlBar.findViewById(R.id.toolbar) ?: return null
        return toolBar.findViewById(R.id.display_url)
    }

    override fun getProgressBar(): ProgressBar? {
        // TODO: we should use ViewBinding to ensure we got the correct instance and type
        // It is a long trip looking for the target view, across Custom View and include-xml
        val binding = this.binding ?: return null
        // layout_collapsing_url_bar.xml
        val collapsingUrlBar: CollapsingUrlBar = binding.urlBar
        return collapsingUrlBar.findViewById(R.id.progress)
    }

    override fun getFullScreenGoneViews() =
        binding?.let { listOf(it.appbar, it.bottomBar, it.tabLayout) } ?: emptyList()

    override fun getFullScreenInvisibleViews(): List<View> {
        val binding = this.binding ?: return emptyList()
        return listOf(binding.viewPager)
    }

    // TODO: getting a View from a Fragment, we shouldn't expect it is NonNull
    override fun getFullScreenContainerView(): ViewGroup = binding!!.videoContainer

    override fun onBackPressed(): Boolean {
        val binding = this.binding ?: return false
        val tabItem = if (tabItems.size > binding.viewPager.currentItem) {
            tabItems[binding.viewPager.currentItem]
        } else {
            null
        }
        val tabView = tabItem?.session?.engineSession?.tabView ?: return false
        if (tabView.canGoBack()) {
            goBack()
            return true
        }

        return false
    }

    private fun setupBottomBar(binding: FragmentShoppingSearchResultTabBinding) {
        binding.bottomBar.setOnItemClickListener { type, position ->
            when (type) {
                ItemType.HOME -> sendHomeIntent(requireContext())
                ItemType.REFRESH -> chromeViewModel.refreshOrStop.call()
                ItemType.NEXT -> chromeViewModel.goNext.call()
                ItemType.SHARE -> chromeViewModel.share.call()
                ItemType.SHOPPING_SEARCH ->
                    shoppingSearchResultViewModel.onShoppingSearchButtonClick()
                else -> throw IllegalArgumentException("Unhandled bottom bar item, type: $type")
            }
        }
        bottomBarItemAdapter =
            BottomBarItemAdapter(binding.bottomBar, BottomBarItemAdapter.Theme.ShoppingSearch)
        val bottomBarViewModel = getActivityViewModel(bottomBarViewModelCreator)
        bottomBarViewModel.items.nonNullObserve(this) {
            bottomBarItemAdapter.setItems(it)
        }

        chromeViewModel.isRefreshing.switchFrom(bottomBarViewModel.items)
            .observe(viewLifecycleOwner) {
                bottomBarItemAdapter.setRefreshing(it == true)

                if (it == true) {
                    telemetryViewModel.onPageLoadingStarted()
                } else {
                    telemetryViewModel.onPageLoadingStopped()
                }
            }
        chromeViewModel.canGoForward.switchFrom(bottomBarViewModel.items)
            .observe(viewLifecycleOwner) { bottomBarItemAdapter.setCanGoForward(it == true) }
    }

    private fun initUrlBar(binding: FragmentShoppingSearchResultTabBinding) {
        binding.urlBar.setTitle(searchKeyword)
        binding.urlBar.setOnClickListener { shoppingSearchResultViewModel.onUrlBarClicked() }
    }

    private fun initViewPager(binding: FragmentShoppingSearchResultTabBinding) {
        shoppingSearchResultViewModel.uiModel.observe(viewLifecycleOwner) { uiModel ->
            tabItems.clear()
            tabItems.addAll(
                uiModel.shoppingSearchSiteList.mapIndexed { index, site ->
                    TabItem(
                        site.title,
                        site.searchUrl,
                        createTabSession(site.searchUrl, index == 0, uiModel.shouldEnableTurboMode)
                    )
                }
            )
            val shoppingSearchTabsAdapter =
                ShoppingSearchTabsAdapter(childFragmentManager, tabItems)

            binding.viewPager.adapter = shoppingSearchTabsAdapter
            binding.viewPager.clearOnPageChangeListeners()
            binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) = Unit

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) = Unit

                override fun onPageSelected(position: Int) {
                    animateToTab(binding, position)
                    selectContentFragment(shoppingSearchTabsAdapter, position)
                    binding.appbar.setExpanded(true)
                    binding.bottomBar.showBottomBar()
                }
            })
            binding.viewPager.setSwipeable(false)
            Looper.myQueue().addIdleHandler {
                if (!isStateSaved && tabItems.isNotEmpty()) {
                    selectContentFragment(shoppingSearchTabsAdapter, 0)
                    // For the shopping search result tabs except the first one, there will be a "currentUrl" changed event to have the initial one url count.
                    // However, there is no such event for the first tab since the url keeps the same after switching it to the current tab.
                    // Do manually compensate to the first focus tab. So it won't have zero url opened count in telemetry.
                    telemetryViewModel.onUrlOpened()
                }
                false
            }
        }
    }

    private fun animateToTab(binding: FragmentShoppingSearchResultTabBinding, newPosition: Int) {
        if (newPosition == TabLayout.Tab.INVALID_POSITION) {
            return
        }

        if (binding.tabLayoutScrollView.windowToken == null ||
            !ViewCompat.isLaidOut(binding.tabLayoutScrollView)
        ) {
            // If we don't have a window token, or we haven't been laid out yet just draw the new
            // position now
            if (scrollAnimator.isRunning) {
                scrollAnimator.cancel()
            }
            val scrollX = calculateScrollXForTab(binding, newPosition, 0F)
            binding.tabLayoutScrollView.scrollTo(scrollX, 0)
            return
        }

        val startScrollX: Int = binding.tabLayoutScrollView.scrollX
        val targetScrollX = calculateScrollXForTab(binding, newPosition, 0F)
        if (startScrollX != targetScrollX) {
            scrollAnimator.setIntValues(startScrollX, targetScrollX)
            scrollAnimator.start()
        }
    }

    private fun calculateScrollXForTab(
        binding: FragmentShoppingSearchResultTabBinding,
        position: Int,
        positionOffset: Float
    ): Int {
        val slidingTabIndicator = binding.tabLayout.getChildAt(0) as ViewGroup
        val selectedChild: View = slidingTabIndicator.getChildAt(position)
        val nextChild: View? =
            if (position + 1 < slidingTabIndicator.childCount) slidingTabIndicator.getChildAt(
                position + 1
            ) else null
        val selectedWidth = selectedChild.width
        val nextWidth = nextChild?.width ?: 0
        val scrollBase: Int =
            selectedChild.left + selectedWidth / 2 - binding.tabLayoutScrollView.width / 2
        val scrollOffset = ((selectedWidth + nextWidth).toFloat() * 0.5f * positionOffset).toInt()
        val isLtr = getLayoutDirection(binding.tabLayout) == ViewCompat.LAYOUT_DIRECTION_LTR
        return if (isLtr) scrollBase + scrollOffset else scrollBase - scrollOffset
    }

    private fun selectContentFragment(adapter: ShoppingSearchTabsAdapter, position: Int) {
        getCurrentSession()?.unregisterObservers()
        val contentFragment = (adapter.getRegisteredFragment(position) as ContentTabFragment)
        contentFragment.switchToFocusTab()
        getCurrentSession()?.register(contentTabObserver)

        if (tabItems.size > position) {
            telemetryViewModel.onTabSelected(tabItems[position].title, tabItems[position].title)
        }

        contentFragment.setOnKeyboardVisibilityChangedListener { visible ->
            if (visible) {
                contentFragment.setOnKeyboardVisibilityChangedListener(null)
                telemetryViewModel.onKeyboardShown()
            }
        }
    }

    private fun createTabSession(url: String, focus: Boolean, enableTurboMode: Boolean): Session {
        val tabId = sessionManager.addTab("https://", TabUtil.argument(null, false, focus))
        val tabSession = sessionManager.getTabs().find { it.id == tabId }!!
        tabSession.engineSession?.tabView?.apply {
            setContentBlockingEnabled(enableTurboMode)
            loadUrl(url)
        }

        return tabSession
    }

    private fun initTabLayout(binding: FragmentShoppingSearchResultTabBinding) {
        binding.tabLayout.setupWithViewPager(binding.viewPager)

        binding.preferenceButton.setOnClickListener {
            shoppingSearchResultViewModel.goPreferences.call()
        }

        shoppingSearchResultViewModel.goPreferences.observe(viewLifecycleOwner) {
            activity?.baseContext?.let {
                startActivity(ShoppingSearchPreferencesActivity.getStartIntent(it))
            }
        }
    }

    private fun observeChromeAction() {
        chromeViewModel.refreshOrStop.observe(viewLifecycleOwner) {
            if (chromeViewModel.isRefreshing.value == true) {
                stop()
            } else {
                reload()
            }
        }
        chromeViewModel.goNext.observe(viewLifecycleOwner) {
            if (chromeViewModel.canGoForward.value == true) {
                goForward()
            }
        }
        chromeViewModel.share.observe(viewLifecycleOwner) {
            chromeViewModel.currentUrl.value?.let { url ->
                onShareClicked(url)
            }
        }

        chromeViewModel.currentUrl.observe(viewLifecycleOwner) {
            binding?.appbar?.setExpanded(true)
            binding?.bottomBar?.showBottomBar()
            telemetryViewModel.onUrlOpened()
        }
    }

    private fun onShareClicked(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "text/plain"
        shareIntent.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)))
    }

    private fun sendHomeIntent(context: Context) {
        val intent = Intent().apply {
            setClassName(context, AppConstants.LAUNCHER_ACTIVITY_ALIAS)
        }
        startActivity(intent)
    }

    private fun goBackToSearchInputPage() {
        findNavController().navigateUp()
    }

    private fun goBack() = sessionManager.focusSession?.engineSession?.goBack()

    private fun goForward() = sessionManager.focusSession?.engineSession?.goForward()

    private fun stop() = sessionManager.focusSession?.engineSession?.stopLoading()

    private fun reload() = sessionManager.focusSession?.engineSession?.reload()

    companion object {
        private const val ANIMATION_DURATION = 300L
    }
}
