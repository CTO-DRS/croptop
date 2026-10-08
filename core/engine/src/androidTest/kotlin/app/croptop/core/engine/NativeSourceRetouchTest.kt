package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.RetouchEffect
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SpotHealType
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSourceRetouchTest {
    private fun accepted(result: CommandResult) = assertTrue(result.toString(), result is CommandResult.Accepted)

    @Test
    fun cloneStrokeCopiesSourceAndPreservesHistory() = sourceStroke(healing = false, selected = false)

    @Test
    fun healingStrokeTransfersSourceTexture() = sourceStroke(healing = true, selected = false)

    @Test
    fun cloneStrokeClipsToSelectionAndCancelPreservesPixels() = sourceStroke(healing = false, selected = true)

    private fun sourceStroke(healing: Boolean, selected: Boolean) {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Source retouch", DocSize(48, 32), background = BackgroundContents.TRANSPARENT))
        try {
            val layer = checkNotNull(document.layers.value.primary)
            accepted(document.fill(FillSource.Solid(RgbaColor.White)))
            val paint = document.beginStroke(StrokeSpec(layer, RgbaColor.Black, diameter = 6f, hardness = 1f))
            paint.add(floatArrayOf(8f, 16f, 1f, 0f, 0f, 0f, 0f))
            accepted(paint.end())
            if (selected) accepted(document.select(SelectionShape.Rect(DocRectF(22f, 14f, 26f, 18f)), antiAlias = false))
            val destination = DocPoint(24f, 16f)
            val outside = DocPoint(27f, 16f)
            val before = document.sample(destination, SampleMode.ACTIVE_LAYER).color
            val outsideBefore = document.sample(outside, SampleMode.ACTIVE_LAYER).color
            val effect = if (healing) RetouchEffect.Heal(DocPoint(8f, 16f)) else RetouchEffect.Clone(DocPoint(8f, 16f))
            val spec = StrokeSpec(layer, RgbaColor.Transparent, diameter = 10f, hardness = 1f)
            val history = document.history.value.current
            val cancelled = document.beginRetouch(spec, effect)
            cancelled.add(floatArrayOf(24f, 16f, 1f, 0f, 0f, 0f, 0f))
            cancelled.cancel()
            assertEquals(history, document.history.value.current)
            assertEquals(before, document.sample(destination, SampleMode.ACTIVE_LAYER).color)
            val stroke = document.beginRetouch(spec, effect)
            stroke.add(floatArrayOf(24f, 16f, 1f, 0f, 0f, 0f, 0f))
            stroke.add(floatArrayOf(28f, 16f, 1f, 0f, 0f, 0f, 0.1f))
            accepted(stroke.end())
            val after = document.sample(destination, SampleMode.ACTIVE_LAYER).color
            assertTrue("source texture should change destination before=$before after=$after", after.r < before.r - 0.1f)
            assertEquals(history + 1, document.history.value.current)
            if (selected) assertEquals(outsideBefore, document.sample(outside, SampleMode.ACTIVE_LAYER).color)
            accepted(document.undo())
            assertEquals(before, document.sample(destination, SampleMode.ACTIVE_LAYER).color)
            accepted(document.redo())
            assertEquals(after, document.sample(destination, SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun spotContentAwareRemovesBlemishWithinSelection() = spotStroke(SpotHealType.CONTENT_AWARE, selected = true)

    @Test
    fun spotProximityMatchRemovesBlemish() = spotStroke(SpotHealType.PROXIMITY_MATCH)

    @Test
    fun spotTextureRemovesBlemishWithStrokeOpacityAppliedOnce() = spotStroke(SpotHealType.CREATE_TEXTURE, opacity = 0.5f)

    @Test
    fun spotHealCanSampleMergedLayersIntoAnEmptyTarget() = spotStroke(SpotHealType.PROXIMITY_MATCH, merged = true)

    private fun spotStroke(type: SpotHealType, selected: Boolean = false, opacity: Float = 1f, merged: Boolean = false) {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Spot healing", DocSize(48, 32), background = BackgroundContents.TRANSPARENT))
        try {
            val base = checkNotNull(document.layers.value.primary)
            accepted(document.fill(FillSource.Solid(RgbaColor.White)))
            val paint = document.beginStroke(StrokeSpec(base, RgbaColor.Black, diameter = 6f, hardness = 1f))
            paint.add(floatArrayOf(24f, 16f, 1f, 0f, 0f, 0f, 0f))
            accepted(paint.end())
            val target = if (merged) {
                LayerId(82002).also { accepted(document.execute(EditCommand.AddLayer(it, "Retouch", LayerKind.PIXEL))) }
            } else {
                base
            }
            if (selected) accepted(document.select(SelectionShape.Rect(DocRectF(22f, 14f, 26f, 18f)), antiAlias = false))
            val point = DocPoint(24f, 16f)
            val outside = DocPoint(27f, 16f)
            val before = document.sample(point, SampleMode.ACTIVE_LAYER).color
            val outsideBefore = document.sample(outside, SampleMode.ACTIVE_LAYER).color
            val history = document.history.value.current
            val stroke = document.beginRetouch(
                StrokeSpec(target, RgbaColor.Transparent, diameter = 10f, hardness = 1f, opacity = opacity),
                RetouchEffect.SpotHeal(type, sampleAll = merged),
            )
            stroke.add(floatArrayOf(24f, 16f, 1f, 0f, 0f, 0f, 0f))
            accepted(stroke.end())
            val after = document.sample(point, SampleMode.ACTIVE_LAYER).color
            assertEquals("$type should reconstruct white surroundings with one opacity blend", opacity, after.r, 0.12f)
            assertTrue(after.a > 0.9f)
            if (selected) assertEquals(outsideBefore, document.sample(outside, SampleMode.ACTIVE_LAYER).color)
            assertEquals(history + 1, document.history.value.current)
            accepted(document.undo())
            assertEquals(before, document.sample(point, SampleMode.ACTIVE_LAYER).color)
            accepted(document.redo())
            assertEquals(after, document.sample(point, SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun cloneSourceOutsideTheCanvasIsRejectedAndLeavesPixelsAlone() {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Extreme source", DocSize(32, 32), background = BackgroundContents.TRANSPARENT))
        try {
            val layer = checkNotNull(document.layers.value.primary)
            accepted(document.fill(FillSource.Solid(RgbaColor.White)))
            val stroke = document.beginRetouch(
                StrokeSpec(layer, RgbaColor.Transparent, diameter = 6f, hardness = 1f),
                RetouchEffect.Clone(DocPoint(Float.MAX_VALUE, 16f)),
            )
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            val result = stroke.end()
            assertTrue(result.toString(), result is CommandResult.Rejected && result.code == app.croptop.core.engine.api.RejectionCode.INVALID_ARGUMENT)
            assertEquals(RgbaColor.White, document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(RgbaColor.White, document.sample(DocPoint(2f, 2f), SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun cloneCanSampleMergedLayersIntoAnEmptyTarget() = mergedSourceStroke(healing = false)

    @Test
    fun healingCanSampleMergedLayersIntoAnEmptyTarget() = mergedSourceStroke(healing = true)

    @Test
    fun mergedHealingAppliesOpacityOnceOnAnEmptyTarget() = mergedSourceStroke(healing = true, opacity = 0.5f)

    @Test
    fun patchSourceUsesSelectedRegionAndSupportsUndoRedo() = patchStroke(contentAware = false)

    @Test
    fun patchContentAwareUsesSelectedRegionAndSupportsUndoRedo() = patchStroke(contentAware = true)

    @Test
    fun redEyeCorrectsRedPupilAndSupportsUndoRedo() {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Red eye", DocSize(48, 32), background = BackgroundContents.TRANSPARENT))
        try {
            val layer = checkNotNull(document.layers.value.primary)
            accepted(document.fill(FillSource.Solid(RgbaColor.White)))
            val pupil = document.beginStroke(StrokeSpec(layer, RgbaColor(1f, 0f, 0f, 1f), diameter = 8f, hardness = 1f))
            pupil.add(floatArrayOf(24f, 16f, 1f, 0f, 0f, 0f, 0f))
            accepted(pupil.end())

            val center = DocPoint(24f, 16f)
            val outside = DocPoint(38f, 16f)
            val before = document.sample(center, SampleMode.ACTIVE_LAYER).color
            val outsideBefore = document.sample(outside, SampleMode.ACTIVE_LAYER).color
            assertTrue("fixture needs a red pupil: $before", before.r > before.g + 0.5f)
            val history = document.history.value.current

            accepted(document.redEye(DocRectF(18f, 10f, 30f, 22f), pupilSize = 0.5f, darken = 0.5f))
            val after = document.sample(center, SampleMode.ACTIVE_LAYER).color
            assertTrue("red channel should be neutralized: $after", after.r < before.r)
            assertEquals(outsideBefore, document.sample(outside, SampleMode.ACTIVE_LAYER).color)
            assertEquals(history + 1, document.history.value.current)
            accepted(document.undo())
            assertEquals(before, document.sample(center, SampleMode.ACTIVE_LAYER).color)
            accepted(document.redo())
            assertEquals(after, document.sample(center, SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun patchStroke(contentAware: Boolean) {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Patch", DocSize(48, 32), background = BackgroundContents.TRANSPARENT))
        try {
            val layer = checkNotNull(document.layers.value.primary)
            accepted(document.fill(FillSource.Solid(RgbaColor.White)))
            val blemish = document.beginStroke(StrokeSpec(layer, RgbaColor.Black, diameter = 8f, hardness = 1f))
            blemish.add(floatArrayOf(24f, 16f, 1f, 0f, 0f, 0f, 0f))
            accepted(blemish.end())
            accepted(document.select(SelectionShape.Rect(DocRectF(20f, 12f, 28f, 20f)), antiAlias = false))
            val center = DocPoint(24f, 16f)
            val outside = DocPoint(30f, 16f)
            val before = document.sample(center, SampleMode.ACTIVE_LAYER).color
            val outsideBefore = document.sample(outside, SampleMode.ACTIVE_LAYER).color
            assertTrue("fixture needs a dark blemish: $before", before.r < 0.1f)
            val history = document.history.value.current

            accepted(document.patch(DocPoint(-16f, 0f), contentAware))
            val after = document.sample(center, SampleMode.ACTIVE_LAYER).color
            assertTrue("patch should replace the selected blemish, contentAware=$contentAware: $after", after.r > 0.8f)
            assertEquals(outsideBefore, document.sample(outside, SampleMode.ACTIVE_LAYER).color)
            assertEquals(history + 1, document.history.value.current)
            accepted(document.undo())
            assertEquals(before, document.sample(center, SampleMode.ACTIVE_LAYER).color)
            accepted(document.redo())
            assertEquals(after, document.sample(center, SampleMode.ACTIVE_LAYER).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun mergedSourceStroke(healing: Boolean, opacity: Float = 1f) {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Merged clone", DocSize(32, 32), background = BackgroundContents.TRANSPARENT))
        try {
            accepted(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))))
            val target = LayerId(82001)
            accepted(document.execute(EditCommand.AddLayer(target, "Empty target", LayerKind.PIXEL)))
            val point = DocPoint(24f, 16f)
            assertEquals(0f, document.sample(point, SampleMode.ACTIVE_LAYER).color.a, 0.001f)
            val stroke = document.beginRetouch(
                StrokeSpec(target, RgbaColor.Transparent, diameter = 6f, hardness = 1f, opacity = opacity),
                if (healing) {
                    RetouchEffect.Heal(DocPoint(8f, 16f), sampleAll = true)
                } else {
                    RetouchEffect.Clone(DocPoint(8f, 16f), sampleAll = true)
                },
            )
            stroke.add(floatArrayOf(24f, 16f, 1f, 0f, 0f, 0f, 0f))
            accepted(stroke.end())
            val after = document.sample(point, SampleMode.ACTIVE_LAYER).color
            assertEquals(1f, after.b, 0.01f)
            assertEquals(opacity, after.a, 0.01f)
            assertEquals("source hue must not darken as coverage fades in", 1f, after.b, 0.01f)
            assertEquals(0f, document.sample(DocPoint(2f, 2f), SampleMode.ACTIVE_LAYER).color.a, 0.001f)
        } finally {
            document.close()
            engine.close()
        }
    }
}
