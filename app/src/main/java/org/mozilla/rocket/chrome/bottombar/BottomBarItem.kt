package org.mozilla.rocket.chrome.bottombar

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import org.mozilla.focus.R
import org.mozilla.focus.databinding.ButtonMoreBinding
import org.mozilla.focus.databinding.ButtonPrivateToNormalBinding
import org.mozilla.focus.databinding.ButtonRefreshBinding
import org.mozilla.focus.databinding.ButtonShoppingSearchBinding
import org.mozilla.focus.databinding.ButtonTrackerBinding
import org.mozilla.focus.tabs.TabCounter
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemType
import org.mozilla.rocket.chrome.BottomBarItemAdapter.Theme
import org.mozilla.rocket.extension.setTint
import org.mozilla.rocket.nightmode.themed.ThemedImageButton

sealed class BottomBarItem(val type: ItemType, private val viewId: Int) {
    var view: View? = null

    fun createView(context: Context, parent: ViewGroup): View {
        return onCreateView(context, parent).apply { id = viewId }
    }

    abstract fun onCreateView(context: Context, parent: ViewGroup): View

    class PrivateHomeItem(type: ItemType, id: Int) : BottomBarItem(type, id) {
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

    class BookmarkItem(
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

    class RefreshItem(
        type: ItemType,
        id: Int,
        private val theme: Theme
    ) : BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            val binding = ButtonRefreshBinding.inflate(inflater, parent, false)
            binding.actionRefresh.setTint(context, theme.buttonColorResId)
            binding.actionStop.setTint(context, theme.buttonColorResId)
            return binding.root
        }
    }

    class MenuItem(
        type: ItemType,
        id: Int,
        private val theme: Theme
    ) : BottomBarItem(type, id) {
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

    class TabCounterItem(
        type: ItemType,
        id: Int,
        private val theme: Theme
    ) :
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

    class TrackerItem(type: ItemType, id: Int) : BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            return ButtonTrackerBinding.inflate(inflater, parent, false).root
        }
    }

    class ShoppingSearchItem(
        type: ItemType,
        id: Int,
        private val theme: Theme
    ) : BottomBarItem(type, id) {
        override fun onCreateView(context: Context, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            val binding = ButtonShoppingSearchBinding.inflate(inflater, parent, false)
            val shoppingSearchColorResId =
                if (theme == Theme.ShoppingSearch)
                    R.color.shoppingSearchIcon
                else
                    theme.buttonColorResId
            binding.actionShoppingSearch.setTint(context, shoppingSearchColorResId)
            return binding.root
        }
    }
}
