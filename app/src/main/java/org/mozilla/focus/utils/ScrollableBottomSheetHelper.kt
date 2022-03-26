package org.mozilla.focus.utils

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.mozilla.focus.R
import kotlin.math.abs

/**
 * Util class to help on making a CoordinateLayout to be expandable by scrolling
 */
object ScrollableBottomSheetHelper {

    fun makeViewScrollable(
        context: Context,
        binding: Binding,
        cornerRadius: Float = context.resources.getDimension(R.dimen.menu_corner_radius),
        menuBottomMargin: Float = context.resources.getDimension(R.dimen.menu_bottom_margin),
        dismissListener: () -> Unit
    ) {
        setRoundedCorner(binding.container, cornerRadius)

        binding.container.setOnClickListener { dismissListener.invoke() }

        setBottomSheetBehavior(
            binding.rootView,
            binding.bottomSheet,
            menuBottomMargin,
            cornerRadius,
            dismissListener
        )
    }

    private fun setRoundedCorner(container: CoordinatorLayout, cornerRadius: Float) {
        container.outlineProvider = RoundedCornerOutlineProvider(cornerRadius)
        container.clipToOutline = true
    }

    private fun setBottomSheetBehavior(
        rootView: ViewGroup,
        bottomSheet: ViewGroup,
        menuBottomMargin: Float,
        cornerRadius: Float,
        dismissListener: () -> Unit
    ) {
        val bottomSheetBehavior: BottomSheetBehavior<ViewGroup> =
            BottomSheetBehavior
                .from(bottomSheet)
                // by default, BottomSheet is showing half part, it is Collapsed
                .also { it.state = BottomSheetBehavior.STATE_COLLAPSED }

        val callback = BottomSheetCallback(
            bottomSheetBehavior,
            rootView,
            menuBottomMargin,
            cornerRadius,
            dismissListener
        )

        bottomSheetBehavior.setBottomSheetCallback(callback)
    }

    private class RoundedCornerOutlineProvider(
        private val cornerRadius: Float
    ) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
        }
    }

    private class BottomSheetCallback(
        val bottomSheetBehavior: BottomSheetBehavior<ViewGroup>,
        val rootView: ViewGroup,
        menuBottomMargin: Float,
        cornerRadius: Float,
        val dismissListener: () -> Unit
    ) : BottomSheetBehavior.BottomSheetCallback() {

        private val maxTranslationY = menuBottomMargin + cornerRadius

        private var translationY = Int.MIN_VALUE.toFloat()
        private var collapseHeight = -1

        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                dismissListener.invoke()
            }
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) {
            var currentTranslationY = 0f

            // BottomSheet is between Hidden and Collapsed
            if (slideOffset < 0) {
                if (collapseHeight < 0) {
                    collapseHeight = bottomSheetBehavior.peekHeight
                }
                currentTranslationY = collapseHeight * -slideOffset
            }

            val translationYChanged = this.translationY.compareTo(currentTranslationY) != 0
            if (translationYChanged) {
                this.translationY = currentTranslationY
                if (abs(currentTranslationY) <= maxTranslationY) {
                    rootView.translationY = currentTranslationY
                } else if (currentTranslationY > maxTranslationY &&
                    rootView.translationY < maxTranslationY
                ) {
                    // In case of fast changing
                    rootView.translationY = maxTranslationY
                }
            }
        }
    }

    class Binding(
        // root View of the fragment
        val rootView: ViewGroup,
        // top-level CoordinatorLayout. It has the grey background, be able to dismiss by touching
        val container: CoordinatorLayout,
        // the expandable bottom sheet
        val bottomSheet: ViewGroup
    )
}
