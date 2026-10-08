package app.croptop.core.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPathStyle
import app.croptop.core.model.VectorSubpath
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShapeLayerExportTest {
    @Test
    fun flattenedPngExportRasterizesEditableShapePaths() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File.createTempFile("croptop-shape-export-", ".png", context.cacheDir)
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec("Vector export", DocSize(48, 40), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f))) is CommandResult.Accepted)
            val shapeId = LayerId(987_650_701L)
            assertTrue(
                document.createShapeLayer(shapeId, "Red rectangle", listOf(shape())) is CommandResult.Accepted,
            )
            assertEquals(RgbaColor(0f, 0f, 1f), document.sampleBelow(shapeId, DocPoint(16f, 16f)).color)
            document.export(ExportTarget.AppFile(output.absolutePath), ExportOptions(format = ExportFormat.PNG))

            val bitmap = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                val inside = bitmap.getPixel(16, 16)
                assertEquals(255, Color.red(inside))
                assertEquals(0, Color.green(inside))
                assertEquals(0, Color.blue(inside))
                assertEquals(255, Color.alpha(inside))
                assertEquals(Color.BLUE, bitmap.getPixel(40, 32))
            } finally {
                bitmap.recycle()
            }
        } finally {
            document.close()
            engine.close()
            output.delete()
        }
    }

    private fun shape() = VectorPathDraft(
        id = VectorPathId(701L),
        name = "Export rectangle",
        subpaths = listOf(
            VectorSubpath(
                anchors = listOf(
                    PathAnchor(PathAnchorId(1L), DocPoint(8f, 8f)),
                    PathAnchor(PathAnchorId(2L), DocPoint(28f, 8f)),
                    PathAnchor(PathAnchorId(3L), DocPoint(28f, 28f)),
                    PathAnchor(PathAnchorId(4L), DocPoint(8f, 28f)),
                ),
                closed = true,
            ),
        ),
        style = VectorPathStyle(fillColor = RgbaColor(1f, 0f, 0f, 1f)),
    )
}
