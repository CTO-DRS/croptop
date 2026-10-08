package app.croptop.core.engine

import app.croptop.core.engine.api.BackgroundSampling
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.LayerProperties
import app.croptop.core.engine.api.RetouchEffect
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BlendIfPair
import app.croptop.core.model.BlendIfRange
import app.croptop.core.model.BlendIfSettings
import app.croptop.core.model.CharacterStyle
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.TextLayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device checks for the History Brush, Background Eraser, per-channel Blend If and variable-font text through the real JNI. */
class NativeCreativeToolsTest {
    private fun accepted(result: CommandResult) = assertTrue(result.toString(), result is CommandResult.Accepted)

    private inline fun withDocument(size: DocSize, block: (app.croptop.core.engine.api.DocumentHandle) -> Unit) {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Creative tools", size, background = BackgroundContents.TRANSPARENT))
        try {
            block(document)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun stroke(document: app.croptop.core.engine.api.DocumentHandle, spec: StrokeSpec, effect: RetouchEffect, vararg points: Pair<Float, Float>) {
        val session = document.beginRetouch(spec, effect)
        points.forEachIndexed { i, (x, y) -> session.add(floatArrayOf(x, y, 1f, 0f, 0f, 0f, i * 0.05f)) }
        accepted(session.end())
    }

    @Test
    fun historyBrushRestoresAnEarlierStateUnderTheBrushOnly() = withDocument(DocSize(64, 48)) { document ->
        val layer = checkNotNull(document.layers.value.primary)
        accepted(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))))
        val redState = document.history.value.current
        accepted(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))))
        val spec = StrokeSpec(layer, RgbaColor.Transparent, diameter = 12f, hardness = 1f)
        stroke(document, spec, RetouchEffect.HistoryPaint(redState), 20f to 24f, 24f to 24f)
        val restored = document.sample(DocPoint(22f, 24f), SampleMode.ACTIVE_LAYER).color
        val untouched = document.sample(DocPoint(50f, 24f), SampleMode.ACTIVE_LAYER).color
        assertTrue("red restored under the brush: $restored", restored.r > 0.9f && restored.b < 0.1f)
        assertTrue("blue kept elsewhere: $untouched", untouched.b > 0.9f && untouched.r < 0.1f)
        assertEquals("History Brush", document.history.value.undoLabel)
        accepted(document.undo())
        assertTrue(document.sample(DocPoint(22f, 24f), SampleMode.ACTIVE_LAYER).color.b > 0.9f)
    }

    @Test
    fun backgroundEraserRemovesTheSampledColourAndKeepsOthers() = withDocument(DocSize(64, 48)) { document ->
        val layer = checkNotNull(document.layers.value.primary)
        accepted(document.fill(FillSource.Solid(RgbaColor(0.9f, 0.1f, 0.1f, 1f))))
        val stripe = document.beginStroke(StrokeSpec(layer, RgbaColor(0.1f, 0.1f, 0.9f, 1f), diameter = 6f, hardness = 1f))
        stripe.add(floatArrayOf(32f, 8f, 1f, 0f, 0f, 0f, 0f))
        stripe.add(floatArrayOf(32f, 40f, 1f, 0f, 0f, 0f, 0.1f))
        accepted(stripe.end())
        val spec = StrokeSpec(layer, RgbaColor.Transparent, diameter = 14f, hardness = 1f)
        stroke(document, spec, RetouchEffect.BackgroundErase(tolerance = 0.2f, contiguous = false, sampling = BackgroundSampling.ONCE), 16f to 24f, 20f to 24f)
        assertEquals(0f, document.sample(DocPoint(16f, 24f), SampleMode.ACTIVE_LAYER).color.a, 0.01f)
        assertEquals(1f, document.sample(DocPoint(56f, 24f), SampleMode.ACTIVE_LAYER).color.a, 0.01f)
        accepted(document.undo())
        assertEquals(1f, document.sample(DocPoint(16f, 24f), SampleMode.ACTIVE_LAYER).color.a, 0.01f)
    }

    @Test
    fun aRedBlendIfSliderHidesTheRedLayerOverBlue() = withDocument(DocSize(32, 32)) { document ->
        val bottom = checkNotNull(document.layers.value.primary)
        accepted(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))))
        val top = LayerId(777)
        accepted(document.execute(EditCommand.AddLayer(top, "Red", LayerKind.PIXEL)))
        accepted(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))))
        assertEquals(1f, document.sample(DocPoint(16f, 16f), SampleMode.MERGED).color.r, 0.05f)
        val hideRed = BlendIfSettings(red = BlendIfPair(self = BlendIfRange(whiteLo = 100, whiteHi = 120)))
        accepted(document.execute(EditCommand.SetLayerProps(top, LayerProperties(blendIf = hideRed))))
        val merged = document.sample(DocPoint(16f, 16f), SampleMode.MERGED).color
        assertTrue("the red layer is hidden, blue shows: $merged", merged.b > 0.9f && merged.r < 0.1f)
        assertTrue(bottom != top)
        accepted(document.undo())
        assertTrue(document.sample(DocPoint(16f, 16f), SampleMode.MERGED).color.r > 0.9f)
    }

    @Test
    fun textWithVariableAxesRendersAtAnyWeightWithoutFailing() = withDocument(DocSize(240, 80)) { document ->
        val light = TextLayerInfo("Hamburg", CharacterStyle(sizePt = 36f, axes = mapOf("wght" to 100f)), origin = DocPoint(8f, 50f))
        val heavy = TextLayerInfo("Hamburg", CharacterStyle(sizePt = 36f, axes = mapOf("wght" to 900f)), origin = DocPoint(8f, 50f))
        accepted(document.createTextLayer(LayerId(900), light))
        accepted(document.setTextLayer(LayerId(900), heavy))
        accepted(document.setTextLayer(LayerId(900), light.copy(character = light.character.copy(axes = mapOf("zzzz" to 3f)))))
        assertEquals(LayerKind.TEXT, document.layers.value.find(LayerId(900))?.kind)
    }
}
