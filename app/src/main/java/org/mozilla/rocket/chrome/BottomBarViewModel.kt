package org.mozilla.rocket.chrome

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.mozilla.focus.utils.AppConfigWrapper
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemData
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType

class BottomBarViewModel : ViewModel() {
    val items = MutableLiveData<List<ItemData>>()
    private var isLandscapeMode = false

    init {
        refresh()
    }

    fun refresh() {
        val configuredItems =
            if (isLandscapeMode) {
                DEFAULT_LANDSCAPE_BOTTOM_BAR_ITEMS
            } else {
                getConfiguredItems() ?: DEFAULT_BOTTOM_BAR_ITEMS
            }
        items.value.let { currentValue ->
            if (configuredItems != currentValue) {
                items.value = configuredItems
            }
        }
    }

    fun onScreenRotatedToLandscape(isLandscapeMode: Boolean) {
        this.isLandscapeMode = isLandscapeMode
        refresh()
    }

    private fun getConfiguredItems(): List<ItemData>? = AppConfigWrapper.getBottomBarItems()

    companion object {
        @JvmStatic
        val DEFAULT_BOTTOM_BAR_ITEMS: List<ItemData> = listOf(
            ItemData(ItemType.HOME),
            ItemData(ItemType.REFRESH),
            ItemData(ItemType.SEARCH),
            ItemData(ItemType.TAB_COUNTER),
            ItemData(ItemType.MENU)
        )

        @JvmStatic
        val DEFAULT_LANDSCAPE_BOTTOM_BAR_ITEMS: List<ItemData> = listOf(
            ItemData(ItemType.HOME),
            ItemData(ItemType.REFRESH),
            ItemData(ItemType.SEARCH),
            ItemData(ItemType.TAB_COUNTER),
            ItemData(ItemType.SHARE)
        )
    }
}
