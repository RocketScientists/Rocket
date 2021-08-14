package org.mozilla.rocket.chrome

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.mozilla.focus.utils.AppConfigWrapper
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemData
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType

class BottomBarViewModel : ViewModel() {
    val items = MutableLiveData<List<ItemData>>()
    var isInPrivateMode = false

    private var isLandscapeMode = false

    init {
        refresh()
    }

    fun refresh() {
        val configuredItems = when {
            isInPrivateMode -> PRIVATE_ITEMS
            !isInPrivateMode && isLandscapeMode -> NORMAL_LANDSCAPE_ITEMS
            !isInPrivateMode && !isLandscapeMode -> NORMAL_PORTRAIT_ITEMS
            else ->
                getConfiguredItems() ?: NORMAL_PORTRAIT_ITEMS
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
        // TODO: add more items
        val PRIVATE_ITEMS: List<ItemData> = listOf(
            ItemData(ItemType.HOME),
            ItemData(ItemType.REFRESH),
            ItemData(ItemType.SEARCH),
            ItemData(ItemType.TAB_COUNTER),
            ItemData(ItemType.TRACKER),
        )

        val NORMAL_PORTRAIT_ITEMS: List<ItemData> = listOf(
            ItemData(ItemType.HOME),
            ItemData(ItemType.REFRESH),
            ItemData(ItemType.SEARCH),
            ItemData(ItemType.TAB_COUNTER),
            ItemData(ItemType.MENU)
        )

        val NORMAL_LANDSCAPE_ITEMS: List<ItemData> = listOf(
            ItemData(ItemType.HOME),
            ItemData(ItemType.REFRESH),
            ItemData(ItemType.SEARCH),
            ItemData(ItemType.TAB_COUNTER),
            ItemData(ItemType.SHARE)
        )
    }
}
