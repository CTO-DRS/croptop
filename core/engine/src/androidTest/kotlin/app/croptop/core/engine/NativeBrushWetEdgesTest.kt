package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBrushWetEdgesTest {
    @Test
    fun wetEdgesDarkenStrokeRimWithoutChangingItsAlpha() = runBlocking {
        val engine = NativeEngine()
        try {
            for (buildUp in listOf(false, true)) {
                suspend fun paint(wetEdges: Boolean): Pair<RgbaColor, RgbaColor> {
                    val document = engine.newDocument(
                        NewDocumentSpec("Wet edge brush", DocSize(40, 40), background = BackgroundContents.TRANSPARENT),
                    )
                    try {
                        val layer: LayerId = checkNotNull(document.layers.value.primary)
                        val stroke = document.beginStroke(
                            StrokeSpec(
                                target = layer,
                                color = RgbaColor(1f, 1f, 1f, 1f),
                                diameter = 18f,
                                hardness = 1f,
                                brush = BrushSettings(size = 18f, hardness = 1f, buildUp = buildUp, wetEdges = wetEdges),
                            ),
                        )
                        stroke.add(floatArrayOf(20f, 20f, 1f, 0f, 0f, 0f, 0f))
                        assertTrue(stroke.end() is CommandResult.Accepted)
                        val center = document.sample(DocPoint(20f, 20f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color
                        val rim = document.sample(DocPoint(28f, 20f), app.croptop.core.engine.api.SampleMode.ACTIVE_LAYER).color
                        return center to rim
                    } finally {
                        document.close()
                    }
                }
                val (plainCenter, plainRim) = paint(wetEdges = false)
                val (wetCenter, wetRim) = paint(wetEdges = true)
                assertTrue("wet edges should darken the rim (buildUp=$buildUp): $plainRim → $wetRim", wetRim.r < plainRim.r - 0.05f)
                assertEquals(plainCenter.a, wetCenter.a, 0.01f)
                assertEquals("wet edge effect must preserve rim alpha (buildUp=$buildUp)", plainRim.a, wetRim.a, 0.01f)
            }
        } finally {
            engine.close()
        }
    }
}
