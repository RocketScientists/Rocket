package org.mozilla.rocket.util

import android.content.Context
import android.net.Uri
import org.mozilla.focus.utils.SearchUtils

object IntentUtil {

    private const val MAX_SHARE_TEXT_SIZE = 4096

    /**
     * Parse a string that is made from external. If the given url is sanity, return a Uri.
     * Otherwise, return null
     */
    fun parseExternalTextToUriString(context: Context, rawString: String?): String? {
        if (rawString == null || rawString.isBlank() || rawString.length > MAX_SHARE_TEXT_SIZE) {
            return null
        }

        val uri = Uri.parse(rawString)
        val isUri = uri != null && (uri.scheme == "http" || uri.scheme == "https")
        return if (isUri) {
            rawString
        } else {
            SearchUtils.createSearchUrl(context, rawString)
        }
    }
}
