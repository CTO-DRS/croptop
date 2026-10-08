package app.croptop.feature.settings

import app.croptop.core.ai.ModelRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiModelsSectionTest {
    @Test
    fun statusTextNamesSizeAndProgress() {
        assertEquals("Not downloaded · 50 MB", statusText(ModelRow.Status.NotInstalled, "50 MB"))
        assertEquals("Ready · 50 MB", statusText(ModelRow.Status.Installed, "50 MB"))
        assertTrue(statusText(ModelRow.Status.Downloading(5L shl 20, 50L shl 20), "50 MB").contains("5 MB of 50 MB"))
        assertTrue(statusText(ModelRow.Status.Failed("offline"), "50 MB").contains("offline"))
        assertTrue(statusText(ModelRow.Status.Partial(1L shl 20), "50 MB").startsWith("Paused"))
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0f, ModelRow.Status.Downloading(0, 0).fraction)
        assertEquals(1f, ModelRow.Status.Downloading(10, 5).fraction)
    }
}
