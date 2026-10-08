package app.croptop.core.engine

import app.croptop.core.model.LayerStyles
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.StyleGradientOverlay
import app.croptop.core.model.StyleGradientStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeLayerStylesBridgeEncodingTest {
    @Test
    fun defaultStylesHaveTheNativeLength() {
        assertEquals(111, NativeLayerStylesBridge.encode(NativeLayerStylesBridge.Styles()).size)
    }

    @Test
    fun gradientStopsExtendTheArrayAndAreBounded() {
        val stops = List(5) { NativeLayerStylesBridge.GradientStop(it / 4f, NativeLayerStylesBridge.Rgba(0f, 0f, 0f)) }
        val five = NativeLayerStylesBridge.encode(
            NativeLayerStylesBridge.Styles(gradientOverlay = NativeLayerStylesBridge.GradientOverlay(stops = stops)),
        )
        assertEquals(111 + 3 * 5, five.size)
        val tooMany = List(17) { NativeLayerStylesBridge.GradientStop(it / 16f, NativeLayerStylesBridge.Rgba(0f, 0f, 0f)) }
        assertFailsWith<IllegalArgumentException> {
            NativeLayerStylesBridge.encode(NativeLayerStylesBridge.Styles(gradientOverlay = NativeLayerStylesBridge.GradientOverlay(stops = tooMany)))
        }
    }

    @Test
    fun modelConversionRejectsGradientStopOverflowInsteadOfTruncating() {
        val stops = List(17) { StyleGradientStop(it / 16f, RgbaColor(0f, 0f, 0f)) }
        val styles = LayerStyles(gradientOverlay = StyleGradientOverlay(enabled = true, stops = stops))
        assertFailsWith<IllegalArgumentException> { NativeLayerStylesBridge.fromModel(styles) }
    }
}
