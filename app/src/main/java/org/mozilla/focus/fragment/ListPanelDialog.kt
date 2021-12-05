/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.fragment

import android.content.DialogInterface
import android.graphics.Outline
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import org.mozilla.focus.R
import org.mozilla.focus.history.BrowsingHistoryFragment
import org.mozilla.focus.screenshot.ScreenshotGridFragment
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelBookmark
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelCapture
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelDownload
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelHistory

class ListPanelDialog : DialogFragment() {
    private var scrollView: NestedScrollView? = null
    private var bookmarksIcon: View? = null
    private var downloadsIcon: View? = null
    private var historyIcon: View? = null
    private var screenshotsIcon: View? = null
    private var bookmarksSelectedIcon: View? = null
    private var downloadsSelectedIcon: View? = null
    private var historySelectedIcon: View? = null
    private var screenshotsSelectedIcon: View? = null

    private var title: TextView? = null
    private var firstLaunch = true

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private var onDismissListener: DialogInterface.OnDismissListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.BottomSheetTheme)
    }

    override fun onResume() {
        super.onResume()
        showItem(requireArguments().getInt(TYPE))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_listpanel_dialog, container, false)
        title = v.findViewById(R.id.title)
        val contentLayout = v.findViewById<View>(R.id.container)
        val cornerRadius = resources.getDimension(R.dimen.menu_corner_radius)
        contentLayout.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        contentLayout.clipToOutline = true

        val bottomSheet = v.findViewById<View>(R.id.bottom_sheet)
        scrollView = v.findViewById<View>(R.id.main_content) as NestedScrollView
        scrollView!!.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
                val pageSize = v.measuredHeight
                // v.getChildAt(0).getMeasuredHeight() - v.getMeasuredHeight() - scrollY is -49dp
                // When scrolled to end due to padding
                val someValue = v.getChildAt(0).measuredHeight - v.measuredHeight - scrollY
                if (scrollY > oldScrollY && someValue < pageSize) {
                    val pf = childFragmentManager.findFragmentById(R.id.main_content)
                        as? PanelFragment ?: return@OnScrollChangeListener
                    if (pf.isVisible) {
                        Thread { pf.tryLoadMore() }.start()
                    }
                }
            }
        )

        val menuBottomMargin = resources.getDimension(R.dimen.menu_bottom_margin)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.setBottomSheetCallback(object : BottomSheetCallback() {
            private var translationY = Int.MIN_VALUE.toFloat()
            private var collapseHeight = -1
            private val maxTranslationY = menuBottomMargin + cornerRadius
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dismissAllowingStateLoss()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                var translationY = 0f
                if (slideOffset < 0) {
                    if (collapseHeight < 0) {
                        collapseHeight = bottomSheetBehavior.getPeekHeight()
                    }
                    translationY = collapseHeight * -slideOffset
                }
                if (java.lang.Float.compare(this.translationY, translationY) != 0) {
                    this.translationY = translationY
                    if (Math.abs(translationY) <= maxTranslationY) {
                        v.translationY = translationY
                    } else if (translationY > maxTranslationY && v.translationY < maxTranslationY) {
                        // In case of fast changing
                        v.translationY = maxTranslationY
                    }
                }
            }
        })
        v.findViewById<View>(R.id.container).setOnClickListener { dismissAllowingStateLoss() }
        bookmarksIcon = v.findViewById(R.id.img_bookmarks)
        bookmarksSelectedIcon = v.findViewById(R.id.img_bookmarks_selected)
        v.findViewById<View>(R.id.bookmarks).setOnClickListener {
            showItem(TYPE_BOOKMARKS)
            showPanelBookmark()
        }
        downloadsIcon = v.findViewById(R.id.img_downloads)
        downloadsSelectedIcon = v.findViewById(R.id.img_downloads_selected)
        v.findViewById<View>(R.id.downloads).setOnClickListener {
            showItem(TYPE_DOWNLOADS)
            showPanelDownload()
        }
        historyIcon = v.findViewById(R.id.img_history)
        historySelectedIcon = v.findViewById(R.id.img_history_selected)
        v.findViewById<View>(R.id.history).setOnClickListener {
            showItem(TYPE_HISTORY)
            showPanelHistory()
        }
        screenshotsIcon = v.findViewById(R.id.img_screenshots)
        screenshotsSelectedIcon = v.findViewById(R.id.img_screenshots_selected)
        v.findViewById<View>(R.id.screenshots).setOnClickListener {
            showItem(TYPE_SCREENSHOTS)
            showPanelCapture()
        }
        return v
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (onDismissListener != null) {
            onDismissListener!!.onDismiss(dialog)
        }
        super.onDismiss(dialog)
    }

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        onDismissListener = listener
    }

    private fun setSelectedItem(selectedItem: Int) {
        requireArguments().putInt(TYPE, selectedItem)
        toggleSelectedItem()
    }

    private fun showItem(type: Int) {
        if (firstLaunch || requireArguments().getInt(TYPE) != type) {
            title!!.setText(getTitle(type))
            setSelectedItem(type)
            showPanelFragment(createFragmentByType(type))
        }
    }

    private fun getTitle(type: Int): Int {
        return when (type) {
            TYPE_DOWNLOADS -> R.string.label_menu_download
            TYPE_HISTORY -> R.string.label_menu_history
            TYPE_SCREENSHOTS -> R.string.label_menu_my_shots
            TYPE_BOOKMARKS -> R.string.label_menu_bookmark
            else -> R.string.label_menu_download
        }
    }

    private fun createFragmentByType(type: Int): PanelFragment {
        return when (type) {
            TYPE_DOWNLOADS -> DownloadsFragment.newInstance()
            TYPE_HISTORY -> BrowsingHistoryFragment.newInstance()
            TYPE_SCREENSHOTS -> ScreenshotGridFragment.newInstance()
            TYPE_BOOKMARKS -> BookmarksFragment.newInstance()
            else -> DownloadsFragment.newInstance()
        }
    }

    private fun showPanelFragment(panelFragment: PanelFragment) {
        // FragmentTransaction.replace does not work since we upgrade appcompat to 1.3.1
        // perhaps the timing of creating View and adding View are changed
        // and the NestedScrollView (R.id.main_content) might have 2 views in a short time, which
        // cause a exception.
        // As a workaround, let's remove previous fragment then adding new fragment step by step.

        // getChildFragmentManager().beginTransaction().replace(R.id.main_content, panelFragment).commit();
        val tag = "MAIN_CONTENT_FRAGMENT"
        val mgr = childFragmentManager
        val prevFrg = mgr.findFragmentByTag(tag)
        if (prevFrg != null) {
            mgr.beginTransaction().remove(prevFrg).commit()
        }
        mgr.beginTransaction().add(R.id.main_content, panelFragment, tag).commit()
    }

    private fun toggleSelectedItem() {
        firstLaunch = false
        bookmarksIcon!!.isSelected = false
        downloadsIcon!!.isSelected = false
        historyIcon!!.isSelected = false
        screenshotsIcon!!.isSelected = false
        bookmarksSelectedIcon!!.visibility = View.INVISIBLE
        downloadsSelectedIcon!!.visibility = View.INVISIBLE
        historySelectedIcon!!.visibility = View.INVISIBLE
        screenshotsSelectedIcon!!.visibility = View.INVISIBLE
        when (requireArguments().getInt(TYPE)) {
            TYPE_BOOKMARKS -> {
                bookmarksIcon!!.isSelected = true
                bookmarksSelectedIcon!!.visibility = View.VISIBLE
            }
            TYPE_DOWNLOADS -> {
                downloadsIcon!!.isSelected = true
                downloadsSelectedIcon!!.visibility = View.VISIBLE
            }
            TYPE_HISTORY -> {
                historyIcon!!.isSelected = true
                historySelectedIcon!!.visibility = View.VISIBLE
            }
            TYPE_SCREENSHOTS -> {
                screenshotsIcon!!.isSelected = true
                screenshotsSelectedIcon!!.visibility = View.VISIBLE
            }
            else ->
                throw RuntimeException("There is no view type " + requireArguments().getInt(TYPE))
        }
    }

    companion object {
        const val TYPE_DOWNLOADS = 1
        const val TYPE_HISTORY = 2
        const val TYPE_SCREENSHOTS = 3
        const val TYPE_BOOKMARKS = 4
        private const val TYPE = "TYPE"
        fun newInstance(type: Int): ListPanelDialog {
            val listPanelDialog = ListPanelDialog()
            val args = Bundle()
            args.putInt(TYPE, type)
            listPanelDialog.arguments = args
            return listPanelDialog
        }
    }
}
