package app.croptop.core.engine

import app.croptop.core.model.DocPoint
import app.croptop.core.model.SamplingStroke
import kotlin.test.Test
import kotlin.test.assertContentEquals

class NativeFillBridgeEncodingTest {
    @Test
    fun strokesEncodeAsCountThenAddRadiusAndPoints() {
        val encoded = NativeFillBridge.encodeStrokes(
            listOf(
                SamplingStroke(listOf(DocPoint(1f, 2f), DocPoint(3f, 4f)), 8f, add = true),
                SamplingStroke(listOf(DocPoint(9f, 9f)), 2f, add = false),
            ),
        )
        assertContentEquals(floatArrayOf(2f, 1f, 8f, 2f, 1f, 2f, 3f, 4f, 0f, 2f, 1f, 9f, 9f), encoded)
        assertContentEquals(FloatArray(0), NativeFillBridge.encodeStrokes(emptyList()))
    }
}
