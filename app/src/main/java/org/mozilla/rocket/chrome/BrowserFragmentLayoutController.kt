package org.mozilla.rocket.chrome

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import androidx.core.view.marginTop
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import org.mozilla.focus.databinding.FragmentBrowserBinding
import kotlin.math.max
import kotlin.math.min

/**
 * A Controller to change layout of components inside MainContent.
 *
 * Basically this controller changes the appearance of topBar, bottomBar and WebView on user
 * scrolling.
 */
class BrowserFragmentLayoutController(val binding: FragmentBrowserBinding) {

    private val topBar: ViewGroup = binding.urlBar
    private val webViewSlot: ViewGroup = binding.webViewSlot
    private val bottomBar: ViewGroup = binding.browserBottomBar

    private var topBarAnimator: ValueAnimator =
        ValueAnimator.ofInt().also { it.duration = ANIMATION_DURATION }
    private var bottomBarAnimator: ViewPropertyAnimator? = null

    private var sizeMeasured: Boolean = false
    private var topViewHeight: Int = 0
    private var topViewOriginalMargin: Int = 0
    private var topViewMarginMax: Int = 0
    private var topViewMarginMin: Int = 0
    private var topViewMarginMiddle: Int = 0

    fun settleViews() {
        if (shouldShowTopBar()) {
            showTopBar()
            showBottomBar()
        } else {
            hideTopBar()
            hideBottomBar()
        }
    }

    fun showTopBar() {
        settleTopBar(true)
    }

    private fun hideTopBar() {
        settleTopBar(false)
    }

    fun showBottomBar() {
        cancelBottomBarAnimator()
        val destinationY = 0f
        bottomBarAnimator = createAnimator(destinationY)
    }

    private fun hideBottomBar() {
        cancelBottomBarAnimator()
        val destinationY = bottomBar.measuredHeight.toFloat()
        bottomBarAnimator = createAnimator(destinationY)
    }

    fun readMeasuredValue() {
        if (sizeMeasured) {
            return
        }
        topViewOriginalMargin = topBar.marginTop
        topViewMarginMax = topBar.marginTop
        topViewMarginMin = topBar.marginTop - topBar.measuredHeight
        topViewMarginMiddle = (topViewMarginMax + topViewMarginMin) / 2
        topViewHeight = topBar.measuredHeight
        sizeMeasured = true
    }

    // return consumed value
    fun onWebViewScrolled(dy: Int): Int {
        if (topBarAnimator.isRunning) {
            topBarAnimator.cancel()
        }
        if (dy == 0) {
            return 0
        }
        val originalMargin = topBar.getTopMargin() ?: return 0

        val expected = originalMargin - dy
        val sanitizedMargin = max(topViewMarginMin, min(topViewMarginMax, expected))

        topBar.setTopMargin(sanitizedMargin)
        webViewSlot.setTopMargin(sanitizedMargin + topViewHeight)

        val scrollingConsumed = sanitizedMargin - originalMargin
        if (scrollingConsumed > 0) {
            showBottomBar()
        } else if (scrollingConsumed < 0) {
            hideBottomBar()
        }
        return scrollingConsumed
    }

    private fun cancelBottomBarAnimator() {
        bottomBarAnimator?.cancel()
        bottomBarAnimator = null
    }

    private fun createAnimator(destinationY: Float): ViewPropertyAnimator {
        return bottomBar.animate()
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(ANIMATOR_INTERPOLATOR)
            .translationY(destinationY)
            .setListener(AnimatorCleaner(this))
    }

    // Use animator to change top margin of topView
    private fun settleTopBar(shouldShowTopBar: Boolean) {
        val topViewCurrentMargin = topBar.getTopMargin() ?: return
        val finalMargin = getTopViewSettleMargin(shouldShowTopBar)

        if (topBarAnimator.isRunning) {
            return
        }

        if (finalMargin == topViewCurrentMargin) {
            return
        }

        topBarAnimator.setIntValues(topViewCurrentMargin, finalMargin)
        topBarAnimator.removeAllUpdateListeners()
        topBarAnimator.addUpdateListener {
            val currentValue = it.animatedValue as? Int ?: finalMargin
            topBar.setTopMargin(currentValue)
            webViewSlot.setTopMargin(currentValue + topViewHeight)
        }

        topBarAnimator.start()
    }

    private fun getTopViewSettleMargin(shouldShowTopBar: Boolean): Int {
        return if (shouldShowTopBar) topViewOriginalMargin else topViewMarginMin
    }

    private fun shouldShowTopBar(): Boolean {
        return topBar.marginTop >= topViewMarginMiddle
    }

    private fun View.getTopMargin(): Int? {
        val params = this.layoutParams as? ViewGroup.MarginLayoutParams
        return params?.topMargin
    }

    private fun View.setTopMargin(topMarginPx: Int) {
        val param = this.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        param.topMargin = topMarginPx
        this.layoutParams = param
    }

    private class AnimatorCleaner(val ctrl: BrowserFragmentLayoutController) :
        AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator?) {
            ctrl.bottomBarAnimator = null
        }
    }

    companion object {
        private const val ANIMATION_DURATION: Long = 500L
        private val ANIMATOR_INTERPOLATOR: TimeInterpolator = LinearOutSlowInInterpolator()
    }
}
