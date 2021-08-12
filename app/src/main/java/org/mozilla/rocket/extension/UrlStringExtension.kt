package org.mozilla.rocket.extension

object UrlStringExtension {

    /**
     * Assume the given String is Url format, remove its fragment
     */
    fun String?.removeUrlFragment(): String? {
        if (this == null) {
            return null
        }
        val endPos: Int = when {
            this.indexOf("#") > 0 -> this.indexOf("#")
            else -> this.length
        }
        return this.substring(0, endPos)
    }
}
