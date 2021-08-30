package org.mozilla.rocket.chrome

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.mozilla.rocket.chrome.bottombar.BottomBarItem.ItemType

class PrivateMenuViewModel() : ViewModel() {

    val bottomItems = MutableLiveData<List<BottomBarItemAdapter.ItemData>>()

    init {
        refresh()
    }

    fun refresh() {
        val configuredBottomBarItems = DEFAULT_MENU_BOTTOM_ITEMS
        bottomItems.value.let { currentValue ->
            if (configuredBottomBarItems != currentValue) {
                bottomItems.value = configuredBottomBarItems
            }
        }
    }

    companion object {
        @JvmStatic
        val DEFAULT_MENU_BOTTOM_ITEMS: List<BottomBarItemAdapter.ItemData> = listOf(
            BottomBarItemAdapter.ItemData(ItemType.BACK),
            BottomBarItemAdapter.ItemData(ItemType.NEXT),
            BottomBarItemAdapter.ItemData(ItemType.SHARE)
        )
    }
}
