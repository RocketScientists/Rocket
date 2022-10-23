/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.preference

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceClickListener
import androidx.preference.PreferenceViewHolder
import org.mozilla.focus.R
import org.mozilla.focus.telemetry.TelemetryWrapper.isTelemetryEnabled
import org.mozilla.focus.telemetry.TelemetryWrapper.setTelemetryEnabled
import org.mozilla.focus.utils.FirebaseHelper.enableAnalytics

/**
 * Ideally we'd extend SwitchPreference, and only do the summary modification. Unfortunately
 * that results in us using an older Switch which animates differently to the (seemingly AppCompat)
 * switches used in the remaining preferences. There's no AppCompat SwitchPreference to extend,
 * so instead we just build our own preference.
 */
class TelemetrySwitchPreference : Preference {
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init()
    }

    private fun init() {
        widgetLayoutResource = R.layout.preference_telemetry
        // We are keeping track of the preference value ourselves.
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        val switchWidget = holder.findViewById(R.id.switch_widget) as SwitchCompat
        switchWidget.isChecked = isTelemetryEnabled(context)
        switchWidget.setOnCheckedChangeListener { buttonView, isChecked ->
            setTelemetryEnabled(context, isChecked)
            // we should use the value from UI (isChecked) instead of relying on SharePreference.
            enableAnalytics(context.applicationContext, isEnabled)
        }

        onPreferenceClickListener = OnPreferenceClickListener {
            switchWidget.toggle()
            true
        }

        super.onBindViewHolder(holder)
    }
}
