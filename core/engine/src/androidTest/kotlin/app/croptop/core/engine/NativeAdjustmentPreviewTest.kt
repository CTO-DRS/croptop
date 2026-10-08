package app.croptop.core.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.AdjustmentCatalog
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.SelectionShape
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAdjustmentPreviewTest {
    @Test
    fun invertPreviewCancelAndCommitHaveCorrectPixelsAndHistory() = kotlinx.coroutines.runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "adjustment-preview.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(32, 64, 96))
        }
        file.outputStream().use { output ->
            ByteArrayOutputStream().use { encoded ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)
                output.write(encoded.toByteArray())
            }
        }
        bitmap.recycle()

        val engine = NativeEngine(context)
        val document = engine.open(DocumentSource.AppFile(file.absolutePath))
        try {
            assertTrue(document.supportsDestructiveAdjustment("invert"))
            assertFalse(document.supportsDestructiveAdjustment("not-a-real-adjustment"))
            val point = DocPoint(0.0f, 0.0f)
            val original = document.sample(point, SampleMode.ACTIVE_LAYER).color

            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 1f, 2f)), antiAlias = false) is CommandResult.Accepted)
            val historyBefore = document.history.value
            val outsideSelection = document.sample(DocPoint(1f, 0f), SampleMode.ACTIVE_LAYER).color
            val selectedPreview = document.beginAdjustmentPreview(AdjustmentInfo("invert"))
            assertTrue(selectedPreview.preview(emptyMap()) is CommandResult.Accepted)
            val insideAfterAdjustment = document.sample(point, SampleMode.ACTIVE_LAYER).color
            val outsideAfterAdjustment = document.sample(DocPoint(1f, 0f), SampleMode.ACTIVE_LAYER).color
            assertEquals(223f / 255f, insideAfterAdjustment.r, 0.001f)
            assertEquals(outsideSelection, outsideAfterAdjustment)
            selectedPreview.cancel()
            assertEquals(original, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            assertEquals(outsideSelection, document.sample(DocPoint(1f, 0f), SampleMode.ACTIVE_LAYER).color)

            AdjustmentCatalog.All.forEach { spec ->
                assertTrue("${spec.id} should be enabled", document.supportsDestructiveAdjustment(spec.id))
                val session = document.beginAdjustmentPreview(AdjustmentInfo(spec.id, spec.defaults()))
                assertTrue("${spec.id} should preview", session.preview(spec.defaults()) is CommandResult.Accepted)
                session.cancel()
            }
            assertEquals(original, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            assertEquals(historyBefore, document.history.value)

            val adjustment = AdjustmentInfo("invert")

            val cancelled = document.beginAdjustmentPreview(adjustment)
            assertTrue(cancelled.preview(emptyMap()) is CommandResult.Accepted)
            val inverted = document.sample(point, SampleMode.ACTIVE_LAYER).color
            assertEquals(223f / 255f, inverted.r, 0.001f)
            assertEquals(191f / 255f, inverted.g, 0.001f)
            assertEquals(159f / 255f, inverted.b, 0.001f)
            assertEquals(historyBefore, document.history.value)
            cancelled.cancel()
            assertEquals(original, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            assertEquals(historyBefore, document.history.value)

            val committed = document.beginAdjustmentPreview(adjustment)
            assertTrue(committed.preview(emptyMap()) is CommandResult.Accepted)
            assertTrue(committed.commit() is CommandResult.Accepted)
            // AutoCloseable.close() delegates to cancel(); closing a committed session must not
            // roll back its pixels or mutate the single committed history entry.
            committed.cancel()
            committed.close()
            assertEquals(223f / 255f, document.sample(point, SampleMode.ACTIVE_LAYER).color.r, 0.001f)
            assertEquals(historyBefore.entries.size + 1, document.history.value.entries.size)
            assertEquals(historyBefore.current + 1, document.history.value.current)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(original, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            assertEquals(historyBefore.current, document.history.value.current)

            val selectedCommit = document.beginAdjustmentPreview(AdjustmentInfo("invert"))
            assertTrue(selectedCommit.preview(emptyMap()) is CommandResult.Accepted)
            assertTrue(selectedCommit.commit() is CommandResult.Accepted)
            assertEquals(223f / 255f, document.sample(point, SampleMode.ACTIVE_LAYER).color.r, 0.001f)
            assertEquals(outsideSelection, document.sample(DocPoint(1f, 0f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(historyBefore.entries.size + 1, document.history.value.entries.size)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(original, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            assertEquals(outsideSelection, document.sample(DocPoint(1f, 0f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(historyBefore.current, document.history.value.current)
        } finally {
            document.close()
            engine.close()
            file.delete()
        }
    }
}
