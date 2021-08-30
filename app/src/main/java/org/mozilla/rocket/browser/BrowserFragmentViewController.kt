package org.mozilla.rocket.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentBrowserBinding
import org.mozilla.rocket.tabs.TabView

private const val ANIMATION_DURATION = 300

class BrowserFragmentViewController(val fragment: BrowserFragment) : LifecycleObserver {

    private var binding: FragmentBrowserBinding? = null
    private var tabTransitionAnimator: ValueAnimator? = null

    private var appBarBgTransition: TransitionDrawable? = null
    private var statusBarBgTransition: TransitionDrawable? = null

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onViewCreated() {
        this.binding = fragment.binding ?: return
        appBarBgTransition = binding?.toolbar?.toolbarRoot?.background as? TransitionDrawable
        statusBarBgTransition = binding?.insetCover?.background as? TransitionDrawable
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroyView() {
        this.binding = null
    }

    fun updateLoadingState(isLoading: Boolean) {
        if (isLoading) {
            appBarBgTransition?.resetTransition()
            statusBarBgTransition?.resetTransition()
        } else {
            appBarBgTransition?.startTransition(ANIMATION_DURATION)
            statusBarBgTransition?.startTransition(ANIMATION_DURATION)
        }
    }

    fun transitToTab(view: View?) {
        val binding = this.binding ?: return
        val inView = view ?: return
        val webViewSlot = binding.webviewSlot
        val outView = webViewSlot.findExistingTabView()

        webViewSlot.removeView(outView)
        webViewSlot.addView(inView)

        startTabTransition(null, inView)
    }

    private fun startTabTransition(outView: View?, inView: View) {
        stopTabTransition()
        inView.alpha = 0f
        outView?.alpha = 1f

        tabTransitionAnimator = createTransitionAnimator(inView, outView)
        tabTransitionAnimator?.start()
    }

    private fun stopTabTransition() {
        val animator = tabTransitionAnimator ?: return
        if (animator.isRunning) {
            animator.end()
        }
        tabTransitionAnimator = null
    }

    private fun createTransitionAnimator(inView: View, outView: View?): ValueAnimator {
        val duration = fragment.resources.getInteger(R.integer.tab_transition_time).toLong()
        val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(duration)
        animator.addUpdateListener { animation ->
            val alpha = animation.animatedValue as Float
            inView.alpha = alpha
            outView?.alpha = 1 - alpha
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                inView.alpha = 1f
                outView?.alpha = 1f
                tabTransitionAnimator = null
            }
        })

        return animator
    }

    private fun ViewGroup?.findExistingTabView(): View? {
        val parent = this ?: return null
        val viewCount = parent.childCount
        for (childIdx in 0 until viewCount) {
            val childView = parent.getChildAt(childIdx)
            if (childView is TabView) {
                return (childView as TabView).getView()
            }
        }
        return null
    }
}
