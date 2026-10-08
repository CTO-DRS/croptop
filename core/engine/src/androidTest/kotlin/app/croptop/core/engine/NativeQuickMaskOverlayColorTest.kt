package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeQuickMaskOverlayColorTest {
    @Test
    fun configurableOverlayColorValidatesAndSchedulesViewportRender() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Quick Mask color", DocSize(32, 24), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertEquals(RgbaColor(1f, 0f, 0f, 0.5f), document.quickMaskOverlayColor.value)
            val color = RgbaColor(0.1f, 0.75f, 0.9f, 0.4f)
            val accepted = document.setQuickMaskOverlayColor(color) as CommandResult.Accepted
            assertEquals(color, document.quickMaskOverlayColor.value)
            assertEquals(
                EngineEvent.Rendered(accepted.sequence),
                withTimeout(5_000) {
                    document.events.first { it is EngineEvent.Rendered && it.sequence == accepted.sequence }
                },
            )

            val nonFinite = document.setQuickMaskOverlayColor(RgbaColor(Float.NaN, 0f, 0f)) as CommandResult.Rejected
            assertEquals(RejectionCode.INVALID_ARGUMENT, nonFinite.code)
            val outOfRange = document.setQuickMaskOverlayColor(RgbaColor(0f, 0f, 0f, 1.1f)) as CommandResult.Rejected
            assertEquals(RejectionCode.INVALID_ARGUMENT, outOfRange.code)
            assertEquals(color, document.quickMaskOverlayColor.value)
        } finally {
            document.close()
            engine.close()
        }
    }
}
