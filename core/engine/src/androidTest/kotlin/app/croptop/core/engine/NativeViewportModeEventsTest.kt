package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeViewportModeEventsTest {
    @Test
    fun quickMaskToggleCorrelatesAppliedAndRenderedEvents() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Quick Mask event lifecycle", DocSize(32, 24), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val accepted = document.setQuickMask(true) as CommandResult.Accepted
            assertEquals(
                EngineEvent.Applied(accepted.sequence, "Quick Mask on"),
                withTimeout(5_000) {
                    document.events.first { it is EngineEvent.Applied && it.sequence == accepted.sequence }
                },
            )
            assertEquals(
                EngineEvent.Rendered(accepted.sequence),
                withTimeout(5_000) {
                    document.events.first { it is EngineEvent.Rendered && it.sequence == accepted.sequence }
                },
            )
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun maskSoloCommandCorrelatesAppliedAndRenderedEvents() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Mask view event lifecycle", DocSize(32, 24), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertEquals(true, document.addLayerMask(layer, MaskFill.REVEAL_ALL) is CommandResult.Accepted)
            document.awaitViewportPreview()
            val accepted = document.setMaskView(layer) as CommandResult.Accepted
            assertEquals(
                EngineEvent.Applied(accepted.sequence, "View Mask"),
                withTimeout(5_000) {
                    document.events.first { it is EngineEvent.Applied && it.sequence == accepted.sequence }
                },
            )
            assertEquals(
                EngineEvent.Rendered(accepted.sequence),
                withTimeout(5_000) {
                    document.events.first { it is EngineEvent.Rendered && it.sequence == accepted.sequence }
                },
            )
        } finally {
            document.close()
            engine.close()
        }
    }
}
