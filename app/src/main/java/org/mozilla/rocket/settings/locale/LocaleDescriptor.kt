package org.mozilla.rocket.settings.locale

import android.util.Log
import org.mozilla.focus.locale.Locales
import org.mozilla.focus.utils.CharacterValidator
import org.mozilla.rocket.settings.LocaleListPreference
import java.text.Collator
import java.util.Locale

class LocaleDescriptor(
    val tag: String,
    val locale: Locale = Locales.parseLocaleCode(tag)
) : Comparable<LocaleDescriptor> {

    lateinit var displayName: String

    constructor(tag: String) : this(tag, Locales.parseLocaleCode(tag)) {

        displayName = LocaleListPreference.languageCodeToNameMap[locale.language]
            ?: locale.getDisplayName(locale)

        if (displayName.isEmpty()) {
            // There's nothing sane we can do.
            Log.w(LOG_TAG, "Display name is empty. Using $locale")
            this.displayName = locale.toString()
            return
        }

        // For now, uppercase the first character of LTR locale names.
        // This is pretty much what Android does. This is a reasonable hack
        // for Bug 1014602, but it won't generalize to all locales.
        val directionality = Character.getDirectionality(displayName[0])
        if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            displayName = displayName
                .substring(0, 1)
                .uppercase(locale) +
                displayName.substring(1)
            return
        }
    }

    override fun toString(): String = displayName

    override fun equals(obj: Any?): Boolean {
        return if (obj is LocaleDescriptor) {
            compareTo(obj) == 0
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return tag.hashCode()
    }

    override fun compareTo(another: LocaleDescriptor): Int {
        // We sort by name, so we use Collator.
        return COLLATOR.compare(displayName, another.displayName)
    }

    /**
     * See Bug 1023451 Comment 10 for the research that led to
     * this method.
     *
     * @return true if this locale can be used for displaying UI
     * on this device without known issues.
     */
    fun isUsable(validator: CharacterValidator?): Boolean {
        if (validator == null) {
            return false
        }

        // Oh, for Java 7 switch statements.
        if (tag == "bn-IN") {
            // Bengali sometimes has an English label if the Bengali script
            // is missing. This prevents us from simply checking character
            // rendering for bn-IN; we'll get a false positive for "B", not "ব".
            //
            // This doesn't seem to affect other Bengali-script locales
            // (below), which always have a label in native script.
            if (!displayName.startsWith("বাংলা")) {
                // We're on an Android version that doesn't even have
                // characters to say বাংলা. Definite failure.
                return false
            }
        }

        // These locales use a script that is often unavailable
        // on common Android devices. Make sure we can show them.
        // See documentation for CharacterValidator.
        // Note that bn-IN is checked here even if it passed above.
        if (tag == "or" || tag == "my" || tag == "pa-IN" || tag == "gu-IN" || tag == "bn-IN") {
            if (validator.characterIsMissingInFont(displayName.substring(0, 1))) {
                return false
            }
        }
        return true
    }

    companion object {
        // We use Locale.US here to ensure a stable ordering of entries.
        private val COLLATOR = Collator.getInstance(Locale.US)
        private const val LOG_TAG = "LocalDescriptor"
    }
}
