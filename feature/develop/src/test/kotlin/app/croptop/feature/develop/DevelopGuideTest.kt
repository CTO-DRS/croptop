package app.croptop.feature.develop

import androidx.compose.ui.geometry.Offset
import app.croptop.core.model.UprightGuide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DevelopGuideTest {
    @Test
    fun `a drag becomes a guide clamped to the frame and a tap does not`() {
        assertEquals(UprightGuide(0.2f, 0.1f, 1f, 0.9f), guideFromDrag(Offset(0.2f, 0.1f), Offset(1.4f, 0.9f)))
        assertNull(guideFromDrag(Offset(0.5f, 0.5f), Offset(0.505f, 0.5f)))
    }

    @Test
    fun `guides stay inside the frame`() {
        assertFailsWith<IllegalArgumentException> { UprightGuide(-0.1f, 0f, 0.5f, 0.5f) }
    }
}
