package org.mozilla.rocket.chrome

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat

/**
 * A Rocket dedicated Behavior. It detects WebView's scrolling, and delegate the events to injected
 * Controller
 */
class ScrollingBehavior<V : View>(
    context: Context,
    attributeSet: AttributeSet?
) : CoordinatorLayout.Behavior<V>(context, attributeSet) {

    private var layoutController: BrowserFragmentLayoutController? = null

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: V,
        directTargetChild: View,
        target: View,
        axes: Int,
        type: Int
    ): Boolean {
        return axes == ViewCompat.SCROLL_AXIS_VERTICAL
    }

    override fun onNestedPreScroll(
        coordinatorLayout: CoordinatorLayout,
        child: V,
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int
    ) {
        // intercept Y axis scroll event
        consumed[1] = layoutController?.onWebViewScrolled(dy) ?: 0
    }

    override fun onStopNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: V,
        target: View,
        type: Int
    ) {
        super.onStopNestedScroll(coordinatorLayout, child, target, type)
        layoutController?.settleViews()
    }

    override fun onLayoutChild(parent: CoordinatorLayout, child: V, layoutDirection: Int): Boolean {
        return super.onLayoutChild(parent, child, layoutDirection).also {
            layoutController?.readMeasuredValue()
        }
    }

    fun injectController(ctrl: BrowserFragmentLayoutController) {
        layoutController = ctrl
    }
}
