/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.widget

import android.content.Context
import android.preference.Preference
import android.preference.Preference.OnPreferenceClickListener
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.Switch
import android.widget.TextView
import org.mozilla.focus.R
import org.mozilla.focus.activity.InfoActivity
import org.mozilla.focus.telemetry.TelemetryWrapper.settingsLearnMoreClickEvent
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.utils.SupportUtils

/**
 * Created by ylai on 2017/9/21.
 */
class TurboSwitchPreference : Preference {

    constructor(
        context: Context?,
        attrs: AttributeSet?
    ) : super(context, attrs) {
        init()
    }

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        widgetLayoutResource = R.layout.preference_turbo

        // We are keeping track of the preference value ourselves.
        isPersistent = false
    }

    override fun onBindView(view: View) {
        super.onBindView(view)
        val switchWidget: Switch = view.findViewById(R.id.switch_widget)
        switchWidget.isChecked = Settings.getInstance(context).shouldUseTurboMode()
        switchWidget.setOnCheckedChangeListener { buttonView, isChecked ->
            Settings.getInstance(context).setTurboMode(isChecked)
        }

        val summary: TextView = view.findViewById(android.R.id.summary)

        val typedValue = TypedValue()
        val styledAttributes = context.obtainStyledAttributes(
            typedValue.data,
            intArrayOf(android.R.attr.textColorLink)
        )
        val color = styledAttributes.getColor(0, 0)
        styledAttributes.recycle()

        summary.setTextColor(color)
        summary.setOnClickListener {
            // This is a hardcoded link: if we ever end up needing more of these links, we should
            // move the link into an xml parameter,
            // but there's no advantage to making it configurable now.
            val url = SupportUtils.getSumoURLForTopic(context, "turbo")
            val title = title.toString()
            val intent = InfoActivity.getIntentFor(context, url, title)
            context.startActivity(intent)
            settingsLearnMoreClickEvent(context.getString(R.string.pref_key_turbo_mode))
        }

        // We still want to allow toggling the pref by touching any part of the pref (except for
        // the "learn more" link)
        onPreferenceClickListener = OnPreferenceClickListener {
            switchWidget.toggle()
            true
        }
    }
}
