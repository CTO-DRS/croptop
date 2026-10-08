package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.RetouchEffect
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRetouchFocusTest {
    @Test
    fun blurFocusStrokeIsNativeAndUndoable() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Native focus", size = DocSize(48, 48), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            document.fill(
                app.croptop.core.model.FillSource.Solid(RgbaColor.White),
                opacity = 1f,
                blendMode = app.croptop.core.model.BlendMode.NORMAL,
            )
            val paint = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor.Black, diameter = 14f, hardness = 1f),
            )
            paint.add(floatArrayOf(24f, 24f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(paint.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            runBlocking { document.awaitViewportPreview() }
            val before = document.sample(DocPoint(29f, 24f), SampleMode.ACTIVE_LAYER).color
            val history = document.history.value.current

            val blur = document.beginRetouch(
                StrokeSpec(target = layer, color = RgbaColor.Black, diameter = 18f, hardness = 0.6f),
                RetouchEffect.Focus(sharpen = false, strength = 1f, protectDetail = false),
            )
            blur.add(floatArrayOf(24f, 24f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(blur.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            runBlocking { document.awaitViewportPreview() }
            val after = document.sample(DocPoint(29f, 24f), SampleMode.ACTIVE_LAYER).color
            assertTrue(
                "native blur should change covered pixels before=$before after=$after",
                after.r > before.r || after.g > before.g || after.b > before.b,
            )
            assertTrue("retouch is one undoable history step", document.history.value.current == history + 1)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun toneAndSpongeRetouchSessionsUseNativeKernels() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Native tone", size = DocSize(48, 48), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            document.fill(
                app.croptop.core.model.FillSource.Solid(RgbaColor(0.5f, 0.4f, 0.4f, 1f)),
                opacity = 1f,
                blendMode = app.croptop.core.model.BlendMode.NORMAL,
            )
            val beforeTone = document.sample(DocPoint(24f, 24f), SampleMode.ACTIVE_LAYER).color
            val dodge = document.beginRetouch(
                StrokeSpec(target = layer, color = RgbaColor.Black, diameter = 18f, hardness = 1f),
                RetouchEffect.Tone(burn = false, exposure = 1f, protectTones = true),
            )
            dodge.add(floatArrayOf(24f, 24f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(dodge.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val afterTone = document.sample(DocPoint(24f, 24f), SampleMode.ACTIVE_LAYER).color
            assertTrue("native dodge should lift luma", afterTone.r > beforeTone.r)

            val beforeSponge = afterTone
            val sponge = document.beginRetouch(
                StrokeSpec(target = layer, color = RgbaColor.Black, diameter = 18f, hardness = 1f),
                RetouchEffect.Sponge(saturate = true, flow = 1f, vibrance = false),
            )
            sponge.add(floatArrayOf(24f, 24f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(sponge.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val afterSponge = document.sample(DocPoint(24f, 24f), SampleMode.ACTIVE_LAYER).color
            assertTrue("native sponge should increase channel separation", afterSponge.r - afterSponge.g > beforeSponge.r - beforeSponge.g)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun smudgeRetouchSessionMovesColourAlongTheStroke() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Native smudge", size = DocSize(48, 48), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            document.fill(
                app.croptop.core.model.FillSource.Solid(RgbaColor.White),
                opacity = 1f,
                blendMode = app.croptop.core.model.BlendMode.NORMAL,
            )
            val paint = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor.Black, diameter = 8f, hardness = 1f),
            )
            paint.add(floatArrayOf(18f, 24f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(paint.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val before = document.sample(DocPoint(28f, 24f), SampleMode.ACTIVE_LAYER).color
            val history = document.history.value.current
            val smudge = document.beginRetouch(
                StrokeSpec(target = layer, color = RgbaColor.Transparent, diameter = 10f, hardness = 1f),
                RetouchEffect.Smudge(strength = 1f, fingerPainting = false),
            )
            smudge.add(floatArrayOf(18f, 24f, 1f, 0f, 0f, 0f, 0f))
            smudge.add(floatArrayOf(28f, 24f, 1f, 0f, 0f, 0f, 0.1f))
            assertTrue(smudge.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val after = document.sample(DocPoint(28f, 24f), SampleMode.ACTIVE_LAYER).color
            assertTrue("native smudge should move dark colour before=$before after=$after", after.r < before.r)
            assertTrue(document.history.value.current == history + 1)
            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(document.sample(DocPoint(28f, 24f), SampleMode.ACTIVE_LAYER).color == before)
            assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(document.sample(DocPoint(28f, 24f), SampleMode.ACTIVE_LAYER).color == after)
            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            val loop = document.beginRetouch(
                StrokeSpec(target = layer, color = RgbaColor.Transparent, diameter = 10f, hardness = 1f),
                RetouchEffect.Smudge(strength = 1f),
            )
            listOf(18f to 24f, 28f to 24f, 28f to 34f, 18f to 34f, 18f to 24f).forEachIndexed { index, point ->
                loop.add(floatArrayOf(point.first, point.second, 1f, 0f, 0f, 0f, index * 0.1f))
            }
            assertTrue(loop.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(
                "a closed stroke must retain its per-dab motion",
                document.sample(DocPoint(28f, 24f), SampleMode.ACTIVE_LAYER).color.r < before.r,
            )
        } finally {
            document.close()
            engine.close()
        }
    }
}
