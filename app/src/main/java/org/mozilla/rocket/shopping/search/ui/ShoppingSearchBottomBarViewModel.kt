package org.mozilla.rocket.shopping.search.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemData
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType

class ShoppingSearchBottomBarViewModel : ViewModel() {
    val items = MutableLiveData<List<ItemData>>()

    init {
        refresh()
    }

    fun refresh() {
        val configuredItems = DEFAULT_CONTENT_TAB_BOTTOM_BAR_ITEMS
        items.value.let { currentValue ->
            if (configuredItems != currentValue) {
                items.value = configuredItems
            }
        }
    }

    companion object {
        @JvmStatic
        val DEFAULT_CONTENT_TAB_BOTTOM_BAR_ITEMS = listOf(
            ItemData(ItemType.HOME),
            ItemData(ItemType.REFRESH),
            ItemData(ItemType.SHOPPING_SEARCH),
            ItemData(ItemType.NEXT),
            ItemData(ItemType.SHARE)
        )
    }
}
