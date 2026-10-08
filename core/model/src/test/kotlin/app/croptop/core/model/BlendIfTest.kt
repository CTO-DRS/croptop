package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlendIfTest {
    @Test
    fun defaultKeepsEverything() {
        assertTrue(BlendIfSettings().isDefault)
        assertFalse(BlendIfSettings(self = BlendIfRange(blackLo = 1, blackHi = 1)).isDefault)
    }

    @Test
    fun rejectsCrossedOrOutOfRangeSliders() {
        assertFailsWith<IllegalArgumentException> { BlendIfRange(blackLo = 50, blackHi = 10) }
        assertFailsWith<IllegalArgumentException> { BlendIfRange(whiteLo = 250, whiteHi = 200) }
        assertFailsWith<IllegalArgumentException> { BlendIfRange(blackHi = 200, whiteLo = 100) }
        assertFailsWith<IllegalArgumentException> { BlendIfRange(whiteHi = 256) }
    }

    @Test
    fun channelPairsAreIndependentAndCountTowardsDefault() {
        val red = BlendIfPair(self = BlendIfRange(whiteLo = 100, whiteHi = 140))
        val settings = BlendIfSettings().with(BlendIfChannel.RED, red)
        assertFalse(settings.isDefault)
        assertEquals(red, settings.pair(BlendIfChannel.RED))
        assertTrue(settings.pair(BlendIfChannel.GREEN).isDefault)
        assertTrue(settings.pair(BlendIfChannel.GRAY).isDefault)
        val gray = settings.with(BlendIfChannel.GRAY, BlendIfPair(under = BlendIfRange(blackHi = 30)))
        assertEquals(BlendIfRange(blackHi = 30), gray.under)
        assertEquals(red, gray.red, "the gray channel leaves the colour channels alone")
        assertTrue(settings.with(BlendIfChannel.RED, BlendIfPair()).isDefault)
    }

    @Test
    fun theThirtyTwoValueLayoutRoundTripsAndStartsWithGray() {
        val settings = BlendIfSettings(
            self = BlendIfRange(10, 40, 180, 220),
            under = BlendIfRange(5, 5, 250, 255),
            red = BlendIfPair(BlendIfRange(20, 30, 200, 210)),
            blue = BlendIfPair(under = BlendIfRange(12, 24, 230, 250)),
        )
        val ints = settings.toInts()
        assertEquals(BlendIfSettings.INTS, ints.size)
        assertEquals(listOf(10, 40, 180, 220, 5, 5, 250, 255), ints.take(8))
        assertEquals(listOf(20, 30, 200, 210), ints.slice(8..11))
        assertEquals(listOf(12, 24, 230, 250), ints.slice(28..31))
        assertEquals(settings, BlendIfSettings.fromInts(ints))
        assertEquals(BlendIfSettings(self = settings.self, under = settings.under), BlendIfSettings.fromInts(ints.copyOf(8)))
    }

    @Test
    fun invalidOrMisSizedLayoutsAreRejectedInsteadOfThrowing() {
        assertNull(BlendIfSettings.fromInts(IntArray(5)))
        val crossed = BlendIfSettings().toInts().also { it[0] = 50; it[1] = 10 }
        assertNull(BlendIfSettings.fromInts(crossed))
        assertEquals(BlendIfSettings(), BlendIfSettings.fromInts(BlendIfSettings().toInts()))
    }
}
