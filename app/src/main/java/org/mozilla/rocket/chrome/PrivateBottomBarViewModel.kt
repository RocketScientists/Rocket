package org.mozilla.rocket.chrome

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.mozilla.focus.utils.AppConfigWrapper
import org.mozilla.rocket.chrome.BottomBarItemAdapter.ItemData
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
            ItemData(BottomBarItemAdapter.ItemType.PRIVATE_HOME),
            ItemData(BottomBarItemAdapter.ItemType.NEXT),
            ItemData(BottomBarItemAdapter.ItemType.DELETE),
            ItemData(BottomBarItemAdapter.ItemType.REFRESH),
            ItemData(BottomBarItemAdapter.ItemType.TRACKER)
        )
    }
}
