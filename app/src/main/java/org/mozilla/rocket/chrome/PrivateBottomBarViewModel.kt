package org.mozilla.rocket.chrome

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.mozilla.focus.utils.AppConfigWrapper
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemData
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType
import java.util.Arrays

class PrivateBottomBarViewModel : ViewModel() {
    val items = MutableLiveData<List<ItemData>>()

    init {
        refresh()
    }

    fun refresh() {
        val configuredItems = getConfiguredItems() ?: DEFAULT_PRIVATE_BOTTOM_BAR_ITEMS
        items.value.let { currentValue ->
            if (configuredItems != currentValue) {
                items.value = configuredItems
            }
        }
    }

    private fun getConfiguredItems(): List<ItemData>? = AppConfigWrapper.getPrivateBottomBarItems()

    companion object {
        @JvmStatic
        val DEFAULT_PRIVATE_BOTTOM_BAR_ITEMS: List<ItemData> = Arrays.asList(
            ItemData(ItemType.PRIVATE_HOME),
            ItemData(ItemType.NEXT),
            ItemData(ItemType.DELETE),
            ItemData(ItemType.REFRESH),
            ItemData(ItemType.TRACKER)
        )
    }
}
