package org.mozilla.rocket.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.rocket.util.IntentUtil.parseExternalTextToUriString
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class IntentUtilTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun testParseExternalTextToUriFail() {
        assertNull(parseExternalTextToUriString(context, null))
        assertNull(parseExternalTextToUriString(context, ""))
        assertNull(parseExternalTextToUriString(context, " "))
        assertNull(parseExternalTextToUriString(context, "\n"))

        // longer than 4096
        val tooLongString = StringBuilder("https://")
        for (i in 0..500) {
            tooLongString.append("0123456789.")
        }
        assertNull(parseExternalTextToUriString(context, tooLongString.toString()))
    }

    @Test
    fun testParseExternalTextToUriNormalCase() {
        assertEquals("http://foo.bar", parseExternalTextToUriString(context, "http://foo.bar"))
        assertEquals("https://foo.bar", parseExternalTextToUriString(context, "https://foo.bar"))
    }

    // TODO: make SearchUtil injectable and create test cases
    fun testParseExternalTextToUriAsCreatingSearchUrl() {
    }
}
