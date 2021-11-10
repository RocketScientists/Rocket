package org.mozilla.rocket.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentBrowserBinding
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.rocket.tabs.TabView

private const val ANIMATION_DURATION = 300

class BrowserFragmentViewController(val fragment: BrowserFragment) : DefaultLifecycleObserver {

    private var binding: FragmentBrowserBinding? = null
    private var tabTransitionAnimator: ValueAnimator? = null

    private var appBarBgTransition: TransitionDrawable? = null
    private var statusBarBgTransition: TransitionDrawable? = null
    private var systemVisibility = ViewUtils.SYSTEM_UI_VISIBILITY_NONE

    // onViewCreated
    override fun onCreate(owner: LifecycleOwner) {
        this.binding = fragment.binding ?: return
        appBarBgTransition = binding?.toolbar?.toolbarRoot?.background as? TransitionDrawable
        statusBarBgTransition = binding?.insetCover?.background as? TransitionDrawable
    }

    // onDestroyView
    override fun onDestroy(owner: LifecycleOwner) {
        this.binding = null
    }

    fun isSystemUiChanged(): Boolean {
        return systemVisibility != ViewUtils.SYSTEM_UI_VISIBILITY_NONE
    }

    fun isInVideoFullScreen(): Boolean {
        return binding?.videoContainer?.visibility == View.VISIBLE
    }

    fun enterVideoFullScreen(videoView: View) {
        val binding = binding ?: return
        // Hide browser UI and web content
        binding.appBar.visibility = View.INVISIBLE
        binding.webviewContainer.visibility = View.INVISIBLE
        binding.browserBottomBar.visibility = View.INVISIBLE

        // Add view to video container and make it visible
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.videoContainer.addView(videoView, params)
        binding.videoContainer.visibility = View.VISIBLE

        // Switch to immersive mode: Hide system bars other UI controls
        systemVisibility = ViewUtils.switchToImmersiveMode(fragment.activity)
    }

    fun exitVideoFullScreen() {
        val binding = binding ?: return

        // Remove custom video views and hide container
        binding.videoContainer.removeAllViews()
        binding.videoContainer.visibility = View.GONE

        // Show browser UI and web content again
        binding.appBar.visibility = View.VISIBLE
        binding.webviewContainer.visibility = View.VISIBLE
        binding.browserBottomBar.visibility = View.VISIBLE
        if (systemVisibility != ViewUtils.SYSTEM_UI_VISIBILITY_NONE) {
            // TODO: check, should we reset systemVisibility after exiting immersive mode?
            ViewUtils.exitImmersiveMode(systemVisibility, fragment.activity)
        }
    }

    // Workaround for full-screen WebView issue that the video doesn't fit the viewport
    // after rotating the device from portrait to landscape and vice versa. It could reduce
    // the issue happened rate by changing the video view layout size to a slight smaller size
    // then add to the full screen size again when the device is rotated.
    fun refreshVideoContainer() {
        val videoContainer = binding?.videoContainer ?: return
        if (videoContainer.visibility != View.VISIBLE) {
            return
        }

        val width = (videoContainer.width * 0.99).toInt()
        val height = (videoContainer.height * 0.99).toInt()
        // height, width interchanged
        val workaroundParams = FrameLayout.LayoutParams(height, width)
        updateVideoContainerWithLayoutParams(workaroundParams)

        videoContainer.post {
            if (videoContainer.visibility != View.VISIBLE) {
                return@post
            }
            val fullParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            updateVideoContainerWithLayoutParams(fullParams)
        }
    }

    private fun updateVideoContainerWithLayoutParams(params: FrameLayout.LayoutParams) {
        val videoContainer = binding?.videoContainer ?: return
        val fullscreenContentView = videoContainer.getChildAt(0) ?: return
        videoContainer.removeAllViews()
        videoContainer.addView(fullscreenContentView, params)
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
