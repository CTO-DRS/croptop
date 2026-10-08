package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DevelopMaskModelTest {
    @Test
    fun masksMakeASettingsObjectNonDefaultAndRoundTripThroughJson() {
        assertTrue(DevelopSettings().isDefault)
        val settings = DevelopSettings(
            masks = listOf(
                LocalMask(
                    name = "Sky",
                    amount = 0.7f,
                    adjust = BasicTone(exposure = -1f),
                    components = listOf(
                        MaskComponent.linear(0.5f, 0f, 0.5f, 0.5f),
                        MaskComponent.brush(listOf(MaskStroke(listOf(MaskPoint(0.1f, 0.2f), MaskPoint(0.3f, 0.4f)), erase = true))).copy(op = MaskOp.SUBTRACT),
                    ),
                ),
            ),
        )
        assertFalse(settings.isDefault)
        val json = Json { encodeDefaults = false }
        assertEquals(settings, json.decodeFromString(DevelopSettings.serializer(), json.encodeToString(DevelopSettings.serializer(), settings)))
    }

    @Test
    fun oldRecipesWithoutMasksStillDecode() {
        val old = """{"version":1,"basic":{"exposure":0.5}}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(DevelopSettings.serializer(), old)
        assertTrue(decoded.masks.isEmpty())
        assertEquals(0.5f, decoded.basic.exposure)
    }

    @Test
    fun validatesRanges() {
        assertFailsWith<IllegalArgumentException> { LocalMask(amount = 1.5f) }
        assertFailsWith<IllegalArgumentException> { MaskComponent(MaskKind.RADIAL, params = listOf(0f)) }
        assertFailsWith<IllegalArgumentException> { MaskStroke(emptyList(), size = 5f) }
        assertEquals(5, MaskKind.MANUAL.size)
        assertEquals(MaskKind.AI, MaskKind.entries.last(), "native code reads the kind by ordinal, so AI stays last")
    }

    @Test
    fun templatesProduceOneComponentOfTheRightKind() {
        MaskKind.MANUAL.forEach { kind ->
            val mask = LocalMaskTemplates.of(kind, 2)
            assertEquals(kind, mask.components.single().kind)
            assertTrue(mask.name.endsWith(" 2"))
        }
    }

    @Test
    fun aiComponentsCarryTheirBitmapAndNothingElseDoes() {
        val bytes = ByteArray(8 * 6) { if (it % 8 < 4) -1 else 0 }
        val bitmap = AiMaskBitmap.of(8, 6, bytes, "Subject")
        val component = MaskComponent.ai(bitmap)
        assertEquals(MaskKind.AI, component.kind)
        assertEquals(bytes.toList(), component.bitmap!!.bytes().toList())
        assertFailsWith<IllegalArgumentException> { MaskComponent(MaskKind.AI) }
        assertFailsWith<IllegalArgumentException> { MaskComponent(MaskKind.RADIAL, bitmap = bitmap) }
        assertFailsWith<IllegalArgumentException> { AiMaskBitmap.of(8, 6, ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { AiMaskBitmap(1, 6, "AA==") }
        assertFailsWith<IllegalArgumentException> { AiMaskBitmap(300, 6, "AA==") }
        assertFailsWith<IllegalArgumentException> { LocalMaskTemplates.of(MaskKind.AI, 1) }
    }

    @Test
    fun aiMasksSurviveTheRecipeJson() {
        val bitmap = AiMaskBitmap.of(4, 4, ByteArray(16) { it.toByte() }, "Sky")
        val settings = DevelopSettings(masks = listOf(LocalMask(name = "Sky", components = listOf(MaskComponent.ai(bitmap)))))
        val json = kotlinx.serialization.json.Json.encodeToString(DevelopSettings.serializer(), settings)
        assertEquals(settings, kotlinx.serialization.json.Json.decodeFromString(DevelopSettings.serializer(), json))
    }
}
