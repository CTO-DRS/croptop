package app.croptop.core.engine.fake

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuickMaskOverlayColorTest {
    @Test
    fun validatesAndPublishesDocumentScopedColor() {
        val engine = FakeEngine()
        val document = engine.newDocument(NewDocumentSpec("Quick Mask", DocSize(32, 24)))
        try {
            assertEquals(RgbaColor(1f, 0f, 0f, 0.5f), document.quickMaskOverlayColor.value)
            val chosen = RgbaColor(0.1f, 0.7f, 0.9f, 0.35f)
            assertIs<CommandResult.Accepted>(document.setQuickMaskOverlayColor(chosen))
            assertEquals(chosen, document.quickMaskOverlayColor.value)
            assertEquals(
                RejectionCode.INVALID_ARGUMENT,
                (document.setQuickMaskOverlayColor(RgbaColor(Float.NaN, 0f, 0f)) as CommandResult.Rejected).code,
            )
            assertEquals(RejectionCode.INVALID_ARGUMENT, (document.setQuickMaskOverlayColor(RgbaColor(0f, 0f, 1.01f)) as CommandResult.Rejected).code)
        } finally {
            document.close()
            engine.close()
        }
    }
}
