package org.mozilla.rocket.chrome.bottombar

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import org.mozilla.focus.R
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
            return LayoutInflater.from(context)
                .inflate(R.layout.button_private_to_normal, parent, false)
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

    class MenuItem(
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
            return LayoutInflater.from(context)
                .inflate(R.layout.button_tracker, parent, false)
        }
    }

    class ShoppingSearchItem(
        type: ItemType,
        id: Int,
        private val theme: Theme
    ) : BottomBarItem(type, id) {
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
}
