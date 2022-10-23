/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.settings

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import androidx.preference.ListPreference
import org.mozilla.focus.R
import org.mozilla.focus.locale.LocaleManager
import org.mozilla.focus.utils.CharacterValidator
import org.mozilla.rocket.settings.locale.LocaleDescriptor
import java.util.Arrays
import java.util.Locale

class LocaleListPreference @JvmOverloads constructor(
    context: Context?,
    attributes: AttributeSet? = null
) : ListPreference(context, attributes) {

    @Volatile
    private var entriesLocale: Locale? = null
    private var characterValidator: CharacterValidator? = null

    /**
     * Not every locale we ship can be used on every device, due to
     * font or rendering constraints.
     *
     * This method filters down the list before generating the descriptor array.
     */
    private val usableLocales: Array<LocaleDescriptor>
        private get() {
            val shippingLocales = LocaleManager.getPackagedLocaleTags(context)
            val initialCount = shippingLocales.size
            val locales: MutableSet<LocaleDescriptor> = HashSet(initialCount)
            for (tag in shippingLocales) {
                val descriptor = LocaleDescriptor(tag)
                if (!descriptor.isUsable(characterValidator)) {
                    Log.w(TAG, "Skipping locale $tag on this device.")
                    continue
                }
                locales.add(descriptor)
            }
            val usableCount = locales.size
            val descriptors = locales.toTypedArray()
            Arrays.sort(descriptors, 0, usableCount)
            return descriptors
        }

    override fun onAttached() {
        super.onAttached()

        // Thus far, missing glyphs are replaced by whitespace, not a box
        // or other Unicode codepoint.
        characterValidator = CharacterValidator(" ")
        buildList()
    }

    override fun getSummary(): CharSequence {
        val value = value
        return if (TextUtils.isEmpty(value)) {
            context.getString(R.string.preference_language_systemdefault)
        } else {
            // We can't trust super.getSummary() across locale changes,
            // apparently, so let's do the same work.
            LocaleDescriptor(value).displayName
        }
    }

    private fun buildList() {
        val currentLocale = Locale.getDefault()
        Log.d(TAG, "Building locales list. Current locale: $currentLocale")
        if (currentLocale == entriesLocale && entries != null) {
            Log.v(TAG, "No need to build list.")
            return
        }
        val descriptors = usableLocales
        val count = descriptors.size
        entriesLocale = currentLocale

        // We leave room for "System default".
        val entries = mutableListOf<String>()
        val values = mutableListOf<CharSequence>()
        entries.add(context.getString(R.string.preference_language_systemdefault))
        values.add("")
        for (i in 0 until count) {
            val displayName = descriptors[i].displayName
            val tag = descriptors[i].tag
            entries.add(displayName)
            values.add(tag)
            Log.v(TAG, "$displayName => $tag")
        }
        setEntries(entries.toTypedArray())
        entryValues = values.toTypedArray()
    }

    companion object {

        const val TAG = "GeckoLocaleList"

        @JvmField
        val languageCodeToNameMap: Map<String, String> = mapOf(
            // Only ICU 57 actually contains the Asturian name for Asturian, even Android 7.1 is still
            // shipping with ICU 56, so we need to override the Asturian name (otherwise displayName will
            // be the current locales version of Asturian, see:
            // https://github.com/mozilla-mobile/focus-android/issues/634#issuecomment-303886118
            "ast" to "Asturianu",
            // On an Android 8.0 device those languages are not known and we need to add the names
            // manually. Loading the resources at runtime works without problems though.
            "cak" to "Kaqchikel",
            "ia" to "Interlingua",
            "meh" to "Tu´un savi ñuu Yasi'í Yuku Iti",
            "mix" to "Tu'un savi",
            "trs" to "Triqui",
            "zam" to "DíɁztè",
            "oc" to "occitan",
            "an" to "Aragonés",
            "tt" to "татарча",
            "wo" to "Wolof",
            "anp" to "अंगिका",
            "ixl" to "Ixil",
            "pai" to "Paa ipai",
            "quy" to "Chanka Qhichwa",
            "ay" to "Aimara",
            "quc" to "K'iche'",
            "tsz" to "P'urhepecha",
            "mai" to "मैथिली/মৈথিলী",
            "jv" to "Basa Jawa",
            "su" to "Basa Sunda",
            "ace" to "Basa Acèh",
            "gor" to "Bahasa Hulontalo",
            "ta" to "தமிழ்",
            "kn" to "ಕನ್ನಡ",
            "ml" to "മലയാളം"
        )
    }
}
