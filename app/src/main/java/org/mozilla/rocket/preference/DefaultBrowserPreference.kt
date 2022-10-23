/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.preference

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import dagger.Lazy
import org.mozilla.focus.R
import org.mozilla.focus.utils.DialogUtils
import org.mozilla.focus.utils.Settings
import org.mozilla.rocket.content.appComponent
import org.mozilla.rocket.content.getActivityViewModel
import org.mozilla.rocket.extension.toFragmentActivity
import org.mozilla.rocket.settings.defaultbrowser.ui.DefaultBrowserHelper
import org.mozilla.rocket.settings.defaultbrowser.ui.DefaultBrowserPreferenceViewModel
import org.mozilla.rocket.settings.defaultbrowser.ui.DefaultBrowserPreferenceViewModel.DefaultBrowserPreferenceUiModel
import javax.inject.Inject

@TargetApi(Build.VERSION_CODES.N)
class DefaultBrowserPreference : Preference {
    @Inject
    lateinit var viewModelCreator: Lazy<DefaultBrowserPreferenceViewModel>

    private lateinit var viewModel: DefaultBrowserPreferenceViewModel
    private lateinit var defaultBrowserHelper: DefaultBrowserHelper

    private var switchView: SwitchCompat? = null

    // Instantiated from XML
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        widgetLayoutResource = R.layout.preference_default_browser
        init()
    }

    // Instantiated from XML
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        widgetLayoutResource = R.layout.preference_default_browser
        init()
    }

    private fun init() {
        appComponent().inject(this)
    }

    override fun onAttached() {
        super.onAttached()
        viewModel = getActivityViewModel(viewModelCreator)
        defaultBrowserHelper = DefaultBrowserHelper(context.toFragmentActivity(), viewModel)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        switchView = holder.findViewById(R.id.switch_widget) as SwitchCompat?

        val hostActivity: FragmentActivity = context.toFragmentActivity()

        viewModel.uiModel.observe(hostActivity) { update(it) }
        viewModel.openDefaultAppsSettings.observe(hostActivity) {
            defaultBrowserHelper.openDefaultAppsSettings()
        }
        viewModel.openAppDetailSettings.observe(hostActivity) {
            defaultBrowserHelper.openAppDetailSettings()
        }
        viewModel.openSumoPage.observe(hostActivity) {
            defaultBrowserHelper.openSumoPage()
        }
        viewModel.triggerWebOpen.observe(hostActivity) {
            defaultBrowserHelper.triggerWebOpen()
        }
        viewModel.openDefaultAppsSettingsTutorialDialog.observe(hostActivity) {
            DialogUtils.showGoToSystemAppsSettingsDialog(context, viewModel)
        }
        viewModel.openUrlTutorialDialog.observe(hostActivity) {
            DialogUtils.showOpenUrlDialog(context, viewModel)
        }
        viewModel.successToSetDefaultBrowser.observe(hostActivity) {
            defaultBrowserHelper.showSuccessMessage()
        }
        viewModel.failToSetDefaultBrowser.observe(hostActivity) {
            defaultBrowserHelper.showFailMessage()
        }
    }

    fun update(uiModel: DefaultBrowserPreferenceUiModel) {
        val capturedSwitchView = switchView ?: return
        capturedSwitchView.isChecked = uiModel.isDefaultBrowser
        Settings.updatePrefDefaultBrowserIfNeeded(
            context,
            uiModel.isDefaultBrowser,
            uiModel.hasDefaultBrowser
        )
    }

    override fun onClick() {
        viewModel.performAction()
    }

    fun onFragmentResume() {
        viewModel.onResume()
    }

    fun onFragmentPause() {
        viewModel.onPause()
    }

    fun performActionFromNotification() {
        viewModel.performActionFromNotification()
    }

    companion object {
        const val EXTRA_RESOLVE_BROWSER = "_intent_to_resolve_browser_"
    }
}
