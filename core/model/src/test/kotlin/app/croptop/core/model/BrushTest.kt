package app.croptop.core.model

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class BrushTest {
    private val line = listOf(BrushSample(0f, 0f, time = 0f), BrushSample(1000f, 0f, time = 1f))

    @Test
    fun dabsFollowSpacing() {
        val s = BrushSettings(size = 40f, spacing = 0.25f, sizeDynamics = Dynamics())
        val dabs = BrushDabs.dabs(s, line)
        // One dab every 10 px over 1000 px, plus the first.
        assertEquals(101, dabs.size)
        dabs.zipWithNext().forEach { (a, b) -> assertEquals(10f, hypot(b.x - a.x, b.y - a.y), 0.01f) }
        assertTrue(dabs.all { it.diameter == 40f && it.alpha == 1f })
    }

    @Test
    fun spacingCarriesAcrossShortSegments() {
        val s = BrushSettings(size = 40f, spacing = 0.25f, sizeDynamics = Dynamics())
        val many = (0..1000 step 3).map { BrushSample(it.toFloat(), 0f) }
        val dabs = BrushDabs.dabs(s, many)
        dabs.zipWithNext().forEach { (a, b) -> assertEquals(10f, b.x - a.x, 0.01f) }
    }

    @Test
    fun pressureDrivesSizeWithMinimumAndCurve() {
        val d = Dynamics(DynamicsInput.PRESSURE, minimum = 0.25f)
        assertEquals(0.25f, d.factor(0f), 1e-5f)
        assertEquals(1f, d.factor(1f), 1e-5f)
        assertEquals(0.625f, d.factor(0.5f), 1e-5f)
        assertEquals(1f, Dynamics().factor(0f))
        val s = BrushSettings(size = 100f, sizeDynamics = d)
        val dabs = BrushDabs.dabs(s, listOf(BrushSample(0f, 0f, pressure = 0f), BrushSample(500f, 0f, pressure = 1f)))
        assertEquals(25f, dabs.first().diameter, 0.01f)
        assertTrue(dabs.last().diameter > 90f)
        assertTrue(dabs.zipWithNext().all { (a, b) -> b.diameter >= a.diameter })
    }

    @Test
    fun speedThinsVelocityBrushesAndOpacityTimesFlow() {
        val s = BrushSettings(size = 50f, flow = 0.5f, opacity = 0.5f, sizeDynamics = Dynamics(DynamicsInput.VELOCITY))
        val slow = BrushDabs.dabs(s, listOf(BrushSample(0f, 0f, time = 0f), BrushSample(100f, 0f, time = 1f)))
        val fast = BrushDabs.dabs(s, listOf(BrushSample(0f, 0f, time = 0f), BrushSample(3000f, 0f, time = 1f)))
        assertTrue(slow[3].diameter > fast[3].diameter)
        assertEquals(0.25f, slow[3].alpha, 1e-5f)
    }

    @Test
    fun scatterAndJitterAreSeededAndBounded() {
        val s = BrushSettings(size = 20f, scatter = 2f, scatterBothAxes = true, count = 3, sizeJitter = 0.5f, sizeDynamics = Dynamics())
        val a = BrushDabs.dabs(s, line, seed = 7)
        assertEquals(a, BrushDabs.dabs(s, line, seed = 7))
        assertTrue(a.all { kotlin.math.abs(it.y) <= 2f * 20f + 0.01f && it.diameter in 10f..20f })
        assertTrue(a.map { it.y }.distinct().size > 10)
    }

    @Test
    fun normalizedClampsAndPresetsRoundTrip() {
        val n = BrushSettings(size = 0f, angle = 270f, count = 99, spacing = 0f).normalized()
        assertEquals(1f, n.size)
        assertEquals(-90f, n.angle)
        assertEquals(16, n.count)
        assertEquals(0.01f, n.spacing)
        val json = Json.encodeToString(BrushPreset.serializer(), BrushPreset.BuiltIn[3])
        assertEquals(BrushPreset.BuiltIn[3], Json.decodeFromString(BrushPreset.serializer(), json))
        assertEquals(BrushPreset.BuiltIn.size, BrushPreset.BuiltIn.map { it.id }.distinct().size)
        assertTrue(BrushPreset.BuiltIn.all { it.builtIn && BrushDabs.dabs(it.settings, BrushDabs.previewStroke(300f, 80f)).isNotEmpty() })
    }
}
