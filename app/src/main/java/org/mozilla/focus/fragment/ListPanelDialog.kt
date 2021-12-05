/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.fragment

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import org.mozilla.focus.R
import org.mozilla.focus.databinding.FragmentListpanelDialogBinding
import org.mozilla.focus.history.BrowsingHistoryFragment
import org.mozilla.focus.screenshot.ScreenshotGridFragment
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelBookmark
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelCapture
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelDownload
import org.mozilla.focus.telemetry.TelemetryWrapper.showPanelHistory
import org.mozilla.focus.utils.ScrollableBottomSheetHelper
import org.mozilla.focus.utils.ScrollableBottomSheetHelper.makeViewScrollable

class ListPanelDialog : DialogFragment() {

    private var firstLaunch = true

    private var viewBinding: FragmentListpanelDialogBinding? = null

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
    ): View = FragmentListpanelDialogBinding.inflate(inflater, container, false).also {
        this.viewBinding = it
        val helperBinding = ScrollableBottomSheetHelper.Binding(
            it.root,
            it.container,
            it.bottomSheet
        )

        makeViewScrollable(this.requireContext(), helperBinding) {
            this.dismissAllowingStateLoss()
        }

        setTopButtonsClickListener(it)
        enableLoadMore(it.mainContent)
    }.root

    override fun onDestroyView() {
        super.onDestroyView()
        viewBinding = null
        onDismissListener = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        onDismissListener?.onDismiss(dialog)
        super.onDismiss(dialog)
    }

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        onDismissListener = listener
    }

    private fun setTopButtonsClickListener(binding: FragmentListpanelDialogBinding) {
        binding.bookmarks.setOnClickListener {
            showItem(TYPE_BOOKMARKS)
            showPanelBookmark()
        }
        binding.downloads.setOnClickListener {
            showItem(TYPE_DOWNLOADS)
            showPanelDownload()
        }
        binding.history.setOnClickListener {
            showItem(TYPE_HISTORY)
            showPanelHistory()
        }
        binding.screenshots.setOnClickListener {
            showItem(TYPE_SCREENSHOTS)
            showPanelCapture()
        }
    }

    private fun enableLoadMore(moreItemsContainer: NestedScrollView) {
        moreItemsContainer.setOnScrollChangeListener(
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
    }

    private fun setSelectedItem(selectedItem: Int) {
        requireArguments().putInt(TYPE, selectedItem)
        toggleSelectedItem()
    }

    private fun showItem(type: Int) {
        if (firstLaunch || requireArguments().getInt(TYPE) != type) {
            viewBinding?.title?.setText(getTitle(type))
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
        val binding = viewBinding ?: return
        firstLaunch = false
        binding.imgBookmarks.isSelected = false
        binding.imgDownloads.isSelected = false
        binding.imgHistory.isSelected = false
        binding.imgScreenshots.isSelected = false
        binding.imgBookmarksSelected.visibility = View.INVISIBLE
        binding.imgDownloadsSelected.visibility = View.INVISIBLE
        binding.imgHistorySelected.visibility = View.INVISIBLE
        binding.imgScreenshotsSelected.visibility = View.INVISIBLE
        when (requireArguments().getInt(TYPE)) {
            TYPE_BOOKMARKS -> {
                binding.imgBookmarks.isSelected = true
                binding.imgBookmarksSelected.visibility = View.VISIBLE
            }
            TYPE_DOWNLOADS -> {
                binding.imgDownloads.isSelected = true
                binding.imgDownloadsSelected.visibility = View.VISIBLE
            }
            TYPE_HISTORY -> {
                binding.imgHistory.isSelected = true
                binding.imgHistorySelected.visibility = View.VISIBLE
            }
            TYPE_SCREENSHOTS -> {
                binding.imgScreenshots.isSelected = true
                binding.imgScreenshotsSelected.visibility = View.VISIBLE
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
