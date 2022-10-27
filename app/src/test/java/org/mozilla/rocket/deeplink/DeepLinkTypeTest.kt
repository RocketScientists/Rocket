package org.mozilla.rocket.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.rocket.deeplink.task.OpenPrivateModeTask
import org.mozilla.rocket.deeplink.task.StartSettingsActivityTask

class DeepLinkTypeTest {

    @Test
    fun `When private mode uri is matched, launch private mode activity`() {
        val deepLinkType = DeepLinkType.parse("rocket://private-mode")

        assertEquals(DeepLinkType.PRIVATE_MODE, deepLinkType)
        assertTrue(deepLinkType.getTaskList()[0] is OpenPrivateModeTask)
    }

    @Test
    fun `When set default browser command uri is matched, show set default browser dialog`() {
        val deepLinkType = DeepLinkType.parse("rocket://command?command=${DeepLinkConstants.COMMAND_SET_DEFAULT_BROWSER}")

        assertEquals(DeepLinkType.COMMAND_SET_DEFAULT_BROWSER, deepLinkType)
        assertTrue(deepLinkType.getTaskList()[0] is StartSettingsActivityTask)
    }
}
