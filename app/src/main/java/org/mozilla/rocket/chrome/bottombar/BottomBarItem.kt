package org.mozilla.rocket.chrome.bottombar

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import org.mozilla.focus.R
import org.mozilla.focus.databinding.ButtonMoreBinding
import org.mozilla.focus.databinding.ButtonPrivateToNormalBinding
import org.mozilla.focus.databinding.ButtonRefreshBinding
import org.mozilla.focus.databinding.ButtonTrackerBinding
import org.mozilla.focus.tabs.TabCounter
import org.mozilla.rocket.chrome.BottomBarItemAdapter.Theme
import org.mozilla.rocket.extension.setTint
import org.mozilla.rocket.nightmode.themed.ThemedImageButton

sealed class BottomBarItem(val type: ItemType, private val viewId: Int) {
    var view: View? = null

    fun createView(context: Context, parent: ViewGroup): View {
        return onCreateView(context, parent).apply { id = viewId }
    }

    abstract fun onCreateView(context: Context, parent: ViewGroup): View

    class PrivateHomeItem : BottomBarItem(ItemType.PRIVATE_HOME, R.id.bottom_bar_private_home) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            return ButtonPrivateToNormalBinding.inflate(inflater, parent, false).root
        }
    }

    open class ImageItem(
        type: ItemType,
        id: Int,
        private val drawableResId: Int,
        private val tintResId: Int
    ) : BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val contextThemeWrapper = ContextThemeWrapper(context, R.style.MainMenuButton)
            return ThemedImageButton(contextThemeWrapper, null, 0).apply {
                layoutParams = ViewGroup.LayoutParams(contextThemeWrapper, null)
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(drawableResId)
                imageTintList = ContextCompat.getColorStateList(contextThemeWrapper, tintResId)
            }
        }
    }

    class HomeItem(@ColorRes buttonColor: Int) :
        ImageItem(ItemType.HOME, R.id.bottom_bar_home, R.drawable.action_home, buttonColor)

    class SearchItem(@ColorRes buttonColor: Int) :
        ImageItem(ItemType.SEARCH, R.id.bottom_bar_search, R.drawable.action_search, buttonColor)

    class CaptureItem(@ColorRes buttonColor: Int) :
        ImageItem(ItemType.CAPTURE, R.id.bottom_bar_capture, R.drawable.action_capture, buttonColor)

    class PinShortcutItem(@ColorRes buttonColor: Int) : ImageItem(
        ItemType.PIN_SHORTCUT,
        R.id.bottom_bar_pin_shortcut,
        R.drawable.action_add_to_home,
        buttonColor
    )

    class ShareItem(@ColorRes buttonColor: Int) :
        ImageItem(ItemType.SHARE, R.id.bottom_bar_share, R.drawable.action_share, buttonColor)

    class NextItem(@ColorRes buttonColor: Int) :
        ImageItem(ItemType.NEXT, R.id.bottom_bar_next, R.drawable.action_next, buttonColor)

    class BackItem(@ColorRes buttonColor: Int) :
        ImageItem(ItemType.BACK, R.id.bottom_bar_back, R.drawable.action_back, buttonColor)

    class DeleteItem(@ColorRes buttonColor: Int) :
        ImageItem(ItemType.DELETE, R.id.bottom_bar_delete, R.drawable.menu_delete, buttonColor)

    class BookmarkItem(theme: Theme) : ImageItem(
        ItemType.BOOKMARK,
        R.id.bottom_bar_bookmark,
        R.drawable.ic_add_bookmark,
        if (theme == Theme.Light)
            R.color.ic_add_bookmark_tint_light
        else
            R.color.ic_add_bookmark_tint_dark
    )

    class RefreshItem(@ColorRes val buttonColor: Int) :
        BottomBarItem(ItemType.REFRESH, R.id.bottom_bar_refresh) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            val binding = ButtonRefreshBinding.inflate(inflater, parent, false)
            binding.actionRefresh.setTint(context, buttonColor)
            binding.actionStop.setTint(context, buttonColor)
            return binding.root
        }
    }

    class MenuItem(private val theme: Theme) : BottomBarItem(ItemType.MENU, R.id.bottom_bar_menu) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            val binding = ButtonMoreBinding.inflate(inflater, parent, false)
            binding.btnMenu.setTint(context, theme.buttonColorResId)
            val downloadColorResId =
                if (theme == Theme.Light) R.color.paletteDarkBlueC100 else theme.buttonColorResId
            binding.downloadUnreadIndicator.setTint(context, downloadColorResId)
            return binding.root
        }
    }

    class TabCounterItem(@ColorRes val buttonColor: Int) :
        BottomBarItem(ItemType.TAB_COUNTER, R.id.bottom_bar_tab_counter) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val contextThemeWrapper = ContextThemeWrapper(context, R.style.MainMenuButton)
            return TabCounter(contextThemeWrapper, null, 0).apply {
                layoutParams = ViewGroup.LayoutParams(contextThemeWrapper, null)
                tintDrawables(ContextCompat.getColorStateList(contextThemeWrapper, buttonColor))
            }
        }
    }

    class TrackerItem(id: Int) : BottomBarItem(ItemType.TRACKER, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            return ButtonTrackerBinding.inflate(inflater, parent, false).root
        }
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
    }
}
