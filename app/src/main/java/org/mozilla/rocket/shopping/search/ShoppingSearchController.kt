package org.mozilla.rocket.shopping.search

import android.view.View
import android.view.ViewStub
import android.widget.Button
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import dagger.Lazy
import org.mozilla.focus.R
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.viewmodel.ShoppingSearchPromptViewModel
import org.mozilla.rocket.browser.BrowserFragment
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.shopping.search.ui.ShoppingSearchActivity
import javax.inject.Inject

class ShoppingSearchController(private val fragment: BrowserFragment) : DefaultLifecycleObserver {

    @Inject
    lateinit var promptMessageViewModelCreator: Lazy<ShoppingSearchPromptViewModel>

    lateinit var shoppingSearchPromptViewModel: ShoppingSearchPromptViewModel
    private lateinit var shoppingSearchPromptMessageBehavior: BottomSheetBehavior<*>

    private var promptViewStub: ViewStub? = null

    // onCreateFragment
    override fun onCreate(owner: LifecycleOwner) {
        fragment.appComponent().inject(this)
        shoppingSearchPromptViewModel = fragment.getActivityViewModel(promptMessageViewModelCreator)
    }

    fun onViewCreated(stub: ViewStub) {
        promptViewStub = stub
        observeShoppingSearchPromptMessageViewModel()
    }

    fun onDestroyView() {
        promptViewStub = null
    }

    fun setVisible() {
        promptViewStub?.visibility = View.VISIBLE
    }

    fun setInvisible() {
        promptViewStub?.visibility = View.INVISIBLE
    }

    fun notifyUrlChanged() {
        shoppingSearchPromptViewModel.checkShoppingSearchPromptVisibility(fragment.chromeUrl)
    }

    private fun observeShoppingSearchPromptMessageViewModel() {
        val context = fragment.context ?: return
        shoppingSearchPromptViewModel.openShoppingSearch.observeOnViewLifecycle {
            context.startActivity(ShoppingSearchActivity.getStartIntent(context))
            ScreenNavigator[context].popToHomeScreen(false)
        }

        shoppingSearchPromptViewModel.promptVisibilityState.observeOnViewLifecycle {
            val viewStub = promptViewStub ?: return@observeOnViewLifecycle
            if (viewStub.parent != null) {
                setupShoppingSearchPrompt(viewStub.inflate())
            }
            if (it is ShoppingSearchPromptViewModel.VisibilityState.Expanded) {
                changeShoppingSearchPromptMessageState(BottomSheetBehavior.STATE_EXPANDED)
            } else {
                changeShoppingSearchPromptMessageState(BottomSheetBehavior.STATE_HIDDEN)
            }
        }

        shoppingSearchPromptViewModel.shoppingSiteList.observeOnViewLifecycle {
            shoppingSearchPromptViewModel.checkShoppingSearchPromptVisibility(fragment.chromeUrl)
        }
    }

    private fun setupShoppingSearchPrompt(view: View) {
        shoppingSearchPromptMessageBehavior =
            BottomSheetBehavior.from(view.findViewById<CoordinatorLayout>(R.id.bottom_sheet))
                .apply {
                    setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                        override fun onStateChanged(bottomSheet: View, newState: Int) {
                            when (newState) {
                                STATE_EXPANDED -> shoppingSearchPromptViewModel.onPromptIsShown()
                                STATE_HIDDEN -> shoppingSearchPromptViewModel.onPromptIsDismissed()
                                STATE_DRAGGING -> shoppingSearchPromptViewModel.onPromptIsDragged()
                            }
                        }

                        override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
                    })
                }
        view.findViewById<Button>(R.id.bottom_sheet_search).setOnClickListener {
            shoppingSearchPromptViewModel.onShoppingSearchPromptButtonClicked()
        }
    }

    private fun changeShoppingSearchPromptMessageState(state: Int) {
        shoppingSearchPromptMessageBehavior.state = state
    }

    private fun <X> LiveData<X>.observeOnViewLifecycle(
        lifecycleOwner: LifecycleOwner = fragment.viewLifecycleOwner,
        observer: Observer<X>
    ) {
        this.observe(lifecycleOwner, observer)
    }
}
