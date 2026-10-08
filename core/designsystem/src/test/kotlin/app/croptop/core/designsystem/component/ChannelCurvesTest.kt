package app.croptop.core.designsystem.component

import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelCurvesTest {
    @Test
    fun `channels start independently and update only the selected channel`() {
        val red = Curve.Identity.withPoint(CurvePoint(0.5f, 0.8f)).first
        val channels = ChannelCurves().withCurve(CurveChannel.RED, red)

        assertEquals(red, channels[CurveChannel.RED])
        assertEquals(Curve.Identity, channels[CurveChannel.RGB])
        assertEquals(Curve.Identity, channels[CurveChannel.GREEN])
        assertEquals(Curve.Identity, channels[CurveChannel.BLUE])
    }

    @Test
    fun `freehand stroke is bounded sorted and keeps untouched curve regions`() {
        val base = Curve.Identity.withPoint(CurvePoint(0.25f, 0.15f)).first
        val result = curveFromFreehand(
            base,
            listOf(CurvePoint(0.4f, 0.7f), CurvePoint(0.6f, 0.8f)),
        )

        assertEquals(65, result.points.size)
        assertTrue(result.points.zipWithNext().all { (left, right) -> right.x > left.x })
        assertEquals(0f, result.points.first().x)
        assertEquals(1f, result.points.last().x)
        assertTrue(result.evaluate(0.5f) > 0.7f)
        assertEquals(base.evaluate(0.2f), result.evaluate(0.2f), 0.02f)
    }

    @Test
    fun `empty or invalid freehand input does not replace the curve`() {
        val base = Curve.Identity

        assertSame(base, curveFromFreehand(base, emptyList()))
        assertSame(base, curveFromFreehand(base, listOf(CurvePoint(Float.NaN, 0.5f))))
    }
}
