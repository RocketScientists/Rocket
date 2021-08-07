package org.mozilla.rocket.chrome

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import org.mozilla.focus.R
import org.mozilla.focus.tabs.TabCounter
import org.mozilla.rocket.content.view.BottomBar
import org.mozilla.rocket.content.view.BottomBar.BottomBarItem
import org.mozilla.rocket.content.view.BottomBar.BottomBarItem.ImageItem
import org.mozilla.rocket.nightmode.themed.ThemedImageButton

class BottomBarItemAdapter(
    private val bottomBar: BottomBar,
    private val theme: Theme = Theme.Light
) {
    private var items: List<BottomBarItem>? = null

    fun setItems(types: List<ItemData>) {
        val hasDuplicate = types.groupBy { it }.size < types.size
        require(!hasDuplicate) { "Cannot set duplicated items to BottomBarItemAdapter" }

        convertToItems(types).let {
            items = it
            bottomBar.setItems(it)
        }
    }

    private fun convertToItems(types: List<ItemData>): List<BottomBarItem> =
        types.map(this::convertToItem)

    private fun convertToItem(itemData: ItemData): BottomBarItem {
        return when (val type = itemData.type) {
            ItemType.TAB_COUNTER -> TabCounterItem(type, R.id.bottom_bar_tab_counter, theme)
            ItemType.MENU -> MenuItem(type, R.id.bottom_bar_menu, theme)
            ItemType.HOME -> ImageItem(
                type,
                R.id.bottom_bar_home,
                R.drawable.action_home,
                theme.buttonColorResId
            )
            ItemType.SEARCH -> ImageItem(
                type,
                R.id.bottom_bar_search,
                R.drawable.action_search,
                theme.buttonColorResId
            )
            ItemType.CAPTURE -> ImageItem(
                type,
                R.id.bottom_bar_capture,
                R.drawable.action_capture,
                theme.buttonColorResId
            )
            ItemType.PIN_SHORTCUT -> ImageItem(
                type,
                R.id.bottom_bar_pin_shortcut,
                R.drawable.action_add_to_home,
                theme.buttonColorResId
            )
            ItemType.BOOKMARK -> BookmarkItem(type, R.id.bottom_bar_bookmark, theme)
            ItemType.REFRESH -> RefreshItem(type, R.id.bottom_bar_refresh, theme)
            ItemType.SHARE -> ImageItem(
                type,
                R.id.bottom_bar_share,
                R.drawable.action_share,
                theme.buttonColorResId
            )
            ItemType.NEXT -> ImageItem(
                type,
                R.id.bottom_bar_next,
                R.drawable.action_next,
                theme.buttonColorResId
            )
            ItemType.PRIVATE_HOME -> PrivateHomeItem(type, R.id.bottom_bar_private_home)
            ItemType.DELETE -> ImageItem(
                type,
                R.id.bottom_bar_delete,
                R.drawable.menu_delete,
                theme.buttonColorResId
            )
            ItemType.TRACKER -> TrackerItem(type, R.id.bottom_bar_tracker)
            ItemType.BACK -> ImageItem(
                type,
                R.id.bottom_bar_back,
                R.drawable.action_back,
                theme.buttonColorResId
            )
            ItemType.SHOPPING_SEARCH -> ShoppingSearchItem(
                type,
                R.id.bottom_bar_shopping_search,
                theme
            )
            else -> error("Unexpected BottomBarItem ItemType: $type")
        }
    }

    fun getItem(type: ItemType): BottomBarItem? = items?.find { it.type == type }

    fun setEnabled(enabled: Boolean) {
        items?.forEach {
            it.view?.let { view ->
                setEnabled(view, enabled)
            }
        }
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    fun setDarkTheme(isNight: Boolean) {
        items?.forEach {
            val view = it.view
            val type = it.type
            when {
                view is ThemedImageButton -> view.setDarkTheme(isNight)
                type == ItemType.TAB_COUNTER -> (view as TabCounter).setDarkTheme(isNight)
                type == ItemType.MENU -> view?.findViewById<ThemedImageButton>(R.id.btn_menu)
                    ?.setDarkTheme(isNight)
                type == ItemType.REFRESH -> {
                    view?.findViewById<ThemedImageButton>(R.id.action_refresh)
                        ?.setDarkTheme(isNight)
                    view?.findViewById<ThemedImageButton>(R.id.action_stop)?.setDarkTheme(isNight)
                }
            }
        }
    }

    @JvmOverloads
    fun setTabCount(count: Int, animationEnabled: Boolean = false) {
        getItem(ItemType.TAB_COUNTER)?.view?.apply {
            this as TabCounter
            if (animationEnabled) {
                setCountWithAnimation(count)
            } else {
                setCount(count)
            }
            if (count > 0) {
                isEnabled = true
                alpha = 1f
            } else {
                isEnabled = false
                alpha = 0.3f
            }
        }
    }

    fun setDownloadState(state: DownloadState) {
        getItem(ItemType.MENU)?.view?.apply {
            val stateIcon = findViewById<ImageView>(R.id.download_unread_indicator)
            val downloadingAnimationView =
                findViewById<LottieAnimationView>(R.id.downloading_indicator)
            when (state) {
                DownloadState.DEFAULT -> {
                    stateIcon.visibility = View.GONE
                    downloadingAnimationView.visibility = View.GONE
                }
                DownloadState.DOWNLOADING -> {
                    stateIcon.visibility = View.GONE
                    downloadingAnimationView.apply {
                        visibility = View.VISIBLE
                        if (!downloadingAnimationView.isAnimating) {
                            playAnimation()
                        }
                    }
                }
                DownloadState.UNREAD -> {
                    stateIcon.apply {
                        visibility = View.VISIBLE
                        setImageResource(R.drawable.notify_download)
                    }
                    downloadingAnimationView.visibility = View.GONE
                }
                DownloadState.WARNING -> {
                    stateIcon.apply {
                        visibility = View.VISIBLE
                        setImageResource(R.drawable.notify_notice)
                    }
                    downloadingAnimationView.visibility = View.GONE
                }
            }
        }
    }

    fun setBookmark(isBookmark: Boolean) {
        getItem(ItemType.BOOKMARK)?.view?.apply {
            isActivated = isBookmark
        }
    }

    fun setRefreshing(isRefreshing: Boolean) {
        getItem(ItemType.REFRESH)?.view?.apply {
            val refreshIcon = findViewById<ThemedImageButton>(R.id.action_refresh)
            val stopIcon = findViewById<ThemedImageButton>(R.id.action_stop)
            if (isRefreshing) {
                refreshIcon.visibility = View.INVISIBLE
                stopIcon.visibility = View.VISIBLE
            } else {
                refreshIcon.visibility = View.VISIBLE
                stopIcon.visibility = View.INVISIBLE
            }
        }
    }

    fun setCanGoForward(canGoForward: Boolean) {
        getItem(ItemType.NEXT)?.view?.apply {
            isEnabled = canGoForward
        }
    }

    fun setCanGoBack(canGoBack: Boolean) {
        getItem(ItemType.BACK)?.view?.apply {
            isEnabled = canGoBack
        }
    }

    fun animatePrivateHome() {
        getItem(ItemType.PRIVATE_HOME)?.view?.apply {
            findViewById<LottieAnimationView>(R.id.pm_home_mask).playAnimation()
        }
    }

    fun endPrivateHomeAnimation() {
        getItem(ItemType.PRIVATE_HOME)?.view?.apply {
            findViewById<LottieAnimationView>(R.id.pm_home_mask).progress = 1f
        }
    }

    fun setTrackerSwitch(isOn: Boolean) {
        getItem(ItemType.TRACKER)?.view?.apply {
            val trackerOn = findViewById<LottieAnimationView>(R.id.btn_tracker_on)
            val trackerOff = findViewById<ImageButton>(R.id.btn_tracker_off)
            if (isOn) {
                trackerOn.visibility = View.VISIBLE
                trackerOff.visibility = View.GONE
            } else {
                trackerOn.visibility = View.GONE
                trackerOff.visibility = View.VISIBLE
            }
        }
    }

    fun setTrackerBadgeEnabled(isEnabled: Boolean) {
        getItem(ItemType.TRACKER)?.view?.apply {
            val trackerOn = findViewById<LottieAnimationView>(R.id.btn_tracker_on)
            if (isEnabled) {
                val isAnimating = trackerOn.isAnimating
                val isFinished = trackerOn.frame >= trackerOn.maxFrame
                if (!isAnimating && !isFinished) {
                    trackerOn.playAnimation()
                }
            } else {
                trackerOn.progress = 0f
            }
        }
    }

    private class TabCounterItem(type: ItemType, id: Int, private val theme: Theme) :
        BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val contextThemeWrapper = ContextThemeWrapper(context, R.style.MainMenuButton)
            return TabCounter(contextThemeWrapper, null, 0).apply {
                layoutParams = ViewGroup.LayoutParams(contextThemeWrapper, null)
                tintDrawables(
                    ContextCompat.getColorStateList(
                        contextThemeWrapper,
                        theme.buttonColorResId
                    )
                )
            }
        }
    }

    private class MenuItem(
        type: ItemType,
        id: Int,
        private val theme: Theme
    ) : BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            return LayoutInflater.from(context)
                .inflate(R.layout.button_more, parent, false)
                .apply {
                    findViewById<ThemedImageButton>(R.id.btn_menu).setTint(
                        context,
                        theme.buttonColorResId
                    )
                    val downloadColorResId =
                        if (theme == Theme.Light)
                            R.color.paletteDarkBlueC100
                        else
                            theme.buttonColorResId
                    findViewById<ThemedImageButton>(R.id.download_unread_indicator).setTint(
                        context,
                        downloadColorResId
                    )
                }
        }
    }

    private class BookmarkItem(
        type: ItemType,
        id: Int,
        theme: Theme
    ) : ImageItem(
        type,
        id,
        R.drawable.ic_add_bookmark,
        if (theme == Theme.Light)
            R.color.ic_add_bookmark_tint_light
        else
            R.color.ic_add_bookmark_tint_dark
    )

    private class RefreshItem(type: ItemType, id: Int, private val theme: Theme) :
        BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            return LayoutInflater.from(context)
                .inflate(R.layout.button_refresh, parent, false).apply {
                    findViewById<ThemedImageButton>(R.id.action_refresh).setTint(
                        context,
                        theme.buttonColorResId
                    )
                    findViewById<ThemedImageButton>(R.id.action_stop).setTint(
                        context,
                        theme.buttonColorResId
                    )
                }
        }
    }

    private class PrivateHomeItem(type: ItemType, id: Int) : BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            return LayoutInflater.from(context)
                .inflate(R.layout.button_private_to_normal, parent, false)
        }
    }

    private class TrackerItem(type: ItemType, id: Int) : BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            return LayoutInflater.from(context)
                .inflate(R.layout.button_tracker, parent, false)
        }
    }

    private class ShoppingSearchItem(type: ItemType, id: Int, private val theme: Theme) :
        BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            return LayoutInflater.from(context)
                .inflate(R.layout.button_shopping_search, parent, false).apply {
                    val shoppingSearchColorResId =
                        if (theme == Theme.ShoppingSearch)
                            R.color.shoppingSearchIcon
                        else
                            theme.buttonColorResId
                    findViewById<ThemedImageButton>(R.id.action_shopping_search).setTint(
                        context,
                        shoppingSearchColorResId
                    )
                }
        }
    }

    sealed class Theme(val buttonColorResId: Int) {
        object Light : Theme(buttonColorResId = R.color.browser_menu_button)
        object Dark : Theme(buttonColorResId = R.color.home_bottom_button)
        object PrivateMode : Theme(buttonColorResId = R.color.private_menu_button)
        object ShoppingSearch : Theme(buttonColorResId = R.color.browser_menu_button)
    }

    enum class DownloadState {
        DEFAULT,
        DOWNLOADING,
        UNREAD,
        WARNING
    }

    enum class ItemType {
        TAB_COUNTER,
        MENU,
        HOME,
        SEARCH,
        CAPTURE,
        PIN_SHORTCUT,
        BOOKMARK,
        REFRESH,
        SHARE,
        NEXT,
        PRIVATE_HOME,
        DELETE,
        TRACKER,
        BACK,
        SHOPPING_SEARCH,
    }

    data class ItemData(val type: ItemType)
}

private fun ImageView.setTint(context: Context, colorResId: Int) {
    val contextThemeWrapper = ContextThemeWrapper(context, 0)
    imageTintList = ContextCompat.getColorStateList(contextThemeWrapper, colorResId)
}
