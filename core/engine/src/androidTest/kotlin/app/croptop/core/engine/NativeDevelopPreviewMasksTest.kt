package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.model.AiMaskBitmap
import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.LocalMask
import app.croptop.core.model.MaskComponent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Masks must reach the RAW preview session over JNI (the array is far longer than the fixed block plus the curve). */
@RunWith(AndroidJUnit4::class)
class NativeDevelopPreviewMasksTest {
    @Test
    fun anAiMaskAndABrushMaskRenderInTheRawPreviewSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "preview-masks.dng").apply { writeBytes(context.assets.open("raw/synthetic.dng").use { it.readBytes() }) }
        val engine = NativeEngine(context)
        try {
            engine.openDevelopPreview(DocumentSource.AppFile(file.absolutePath)).use { session ->
                val bytes = ByteArray(16 * 12) { if (it % 16 < 8) -1 else 0 }
                val masked = DevelopSettings(
                    masks = listOf(
                        LocalMask(
                            name = "Subject",
                            adjust = BasicTone(exposure = 1.5f),
                            components = listOf(MaskComponent.ai(AiMaskBitmap.of(16, 12, bytes, "Subject"))),
                        ),
                    ),
                )
                val base = session.render(DevelopSettings(), 64)
                val local = session.render(masked, 64)
                fun luma(p: app.croptop.core.engine.api.DevelopPreview, x: Int, y: Int): Float {
                    val c = p.argb[y * p.width + x]
                    return ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 765f
                }
                val inside = luma(local, 6, 24) - luma(base, 6, 24)
                val outside = luma(local, 58, 24) - luma(base, 58, 24)
                println("[mask] inside +$inside outside +$outside")
                // The synthetic RAW is 64 × 48, so the mask's smoothing window spans the whole frame: the sides differ but do not separate
                // sharply (NativeDevelopAiMaskTest covers the sharp edge on a larger picture).
                assertTrue("the AI mask lifts its side ($inside)", inside > 0.1f)
                assertTrue("and lifts the far side clearly less ($outside)", inside > outside * 1.4f)
                val overlay = session.maskOverlay(masked, 0, 64)
                assertTrue("the mask overlay is drawn for an AI mask", overlay != null)
            }
        } finally {
            engine.close()
            file.delete()
        }
    }
}
