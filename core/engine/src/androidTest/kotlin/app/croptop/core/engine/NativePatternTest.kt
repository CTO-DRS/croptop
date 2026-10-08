package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePatternTest {
    @Test
    fun definePatternCapturesVisiblePixelsAndPersistsInLibrary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "Pattern contract ${System.nanoTime()}"
        val firstEngine = NativeEngine(context)
        val document = firstEngine.newDocument(
            NewDocumentSpec(name = "Pattern source", size = DocSize(32, 24), background = BackgroundContents.TRANSPARENT),
        )
        val saved = try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor(0.9f, 0.2f, 0.1f), diameter = 8f),
            )
            stroke.add(floatArrayOf(16f, 12f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is CommandResult.Accepted)

            assertTrue(document.definePattern(name) is CommandResult.Accepted)
            val pattern = firstEngine.patterns.value.single { it.name == name }
            assertNotNull(firstEngine.patternThumbnail(pattern.id, 24))
            pattern
        } finally {
            document.close()
            firstEngine.close()
        }

        val reopenedEngine = NativeEngine(context)
        try {
            assertTrue(reopenedEngine.patterns.value.any { it.id == saved.id && it.name == name })
        } finally {
            reopenedEngine.deletePattern(saved.id)
            reopenedEngine.close()
        }
    }
}
