package app.croptop.core.engine.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HistogramTest {
    @Test
    fun computesWeightedChannelAndRgbStatisticsAndSerializesThem() {
        val red = List(256) { index ->
            if (index == 0) {
                0.25
            } else if (index == 255) {
                0.75
            } else {
                0.0
            }
        }
        val green = List(256) { index -> if (index == 64) 1.0 else 0.0 }
        val blue = List(256) { 0.0 }
        val luminosity = List(256) { index -> if (index == 77) 1.0 else 0.0 }
        val histogram = Histogram.fromChannels(red, green, blue, luminosity)

        assertEquals(1.0, histogram.statistics.getValue(HistogramChannel.RED).weightedPixels, 0.0)
        assertEquals(191.25, histogram.statistics.getValue(HistogramChannel.RED).mean, 0.0)
        assertEquals(255, histogram.statistics.getValue(HistogramChannel.RED).median)
        assertEquals(64.0, histogram.statistics.getValue(HistogramChannel.GREEN).mean, 0.0)
        assertEquals(64, histogram.statistics.getValue(HistogramChannel.GREEN).median)
        assertEquals(77.0, histogram.statistics.getValue(HistogramChannel.LUMINOSITY).mean, 0.0)
        assertEquals(77, histogram.statistics.getValue(HistogramChannel.LUMINOSITY).median)
        assertEquals(2.0 / 3.0, histogram.statistics.getValue(HistogramChannel.RGB).weightedPixels, 0.0)
        assertEquals(127.625, histogram.statistics.getValue(HistogramChannel.RGB).mean, 0.0)
        assertEquals(64, histogram.statistics.getValue(HistogramChannel.RGB).median)
        assertEquals(
            kotlin.math.sqrt(10_144.234375),
            histogram.statistics.getValue(HistogramChannel.RGB).standardDeviation,
            1e-12,
        )

        val json = Json.encodeToString(histogram)
        assertEquals(histogram, Json.decodeFromString<Histogram>(json))
    }

    @Test
    fun rejectsInvalidBinShapesAndWeights() {
        val zeros = List(256) { 0.0 }
        assertThrows(IllegalArgumentException::class.java) {
            Histogram.fromChannels(zeros.dropLast(1), zeros, zeros, zeros)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Histogram.fromChannels(zeros.toMutableList().also { it[7] = Double.NaN }, zeros, zeros, zeros)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Histogram.fromChannels(
                zeros.toMutableList().also {
                    it[0] = Double.MAX_VALUE
                    it[1] = Double.MAX_VALUE
                },
                zeros,
                zeros,
                zeros,
            )
        }
    }

    @Test
    fun copiesMutableInputsBeforeDerivingStatistics() {
        val mutable = MutableList(256) { 0.0 }.also { it[42] = 1.0 }
        val zeros = List(256) { 0.0 }
        val histogram = Histogram.fromChannels(mutable, zeros, zeros, zeros)

        mutable[42] = 0.0

        assertEquals(1.0, histogram.red[42], 0.0)
        assertEquals(42.0, histogram.statistics.getValue(HistogramChannel.RED).mean, 0.0)
    }
}
