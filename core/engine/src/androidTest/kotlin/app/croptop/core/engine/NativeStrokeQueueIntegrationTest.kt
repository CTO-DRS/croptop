package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeStrokeQueueIntegrationTest {
    @Test
    fun queueOverflowRejectsWholeStrokeWithoutCommittingQueuedPrefix() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Rejected streamed stroke",
                size = DocSize(24, 24),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val baseline = document.history.value.current
            val stroke = document.beginStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor(1f, 0f, 0f, 1f),
                    diameter = 6f,
                    hardness = 1f,
                ),
            )
            stroke.add(floatArrayOf(8f, 12f, 1f, 0f, 0f, 0f, 0f))

            // The per-document queue caps copied chunks at 2 MiB. Keep the request under the
            // per-stroke sample limit while exceeding that byte bound by one seven-float sample.
            val floatsPerSample = 7
            val maxQueueSamples = (2 * 1024 * 1024) / (Float.SIZE_BYTES * floatsPerSample)
            val overflow = FloatArray((maxQueueSamples + 1) * floatsPerSample)
            stroke.add(overflow)
            val result = stroke.end()

            assertEquals(RejectionCode.QUEUE_FULL, (result as CommandResult.Rejected).code)
            assertEquals("overflow must never commit an accepted prefix", baseline, document.history.value.current)
            assertEquals(0f, document.sample(DocPoint(8f, 12f), SampleMode.ACTIVE_LAYER).color.a, 0f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun closingDocumentCancelsOpenStrokeAndJoinsNativeWorkerBeforeDestroy() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Closed during streamed stroke",
                size = DocSize(24, 24),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor(0f, 0f, 1f, 1f),
                    diameter = 6f,
                    hardness = 1f,
                ),
            )
            stroke.add(floatArrayOf(12f, 12f, 1f, 0f, 0f, 0f, 0f))

            document.close()
            assertTrue("close cancels an unfinished stroke", runCatching { stroke.end() }.isFailure)
        } finally {
            document.close()
            engine.close()
        }
    }
}
