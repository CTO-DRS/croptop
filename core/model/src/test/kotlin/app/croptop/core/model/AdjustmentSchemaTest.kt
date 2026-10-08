package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AdjustmentSchemaTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun catalogCoversThePlanAndIsConsistent() {
        val ids = AdjustmentCatalog.All.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(
            ids.containsAll(
                listOf("levels", "curves", "hueSaturation", "colorBalance", "blackWhite", "photoFilter", "invert", "posterize", "threshold"),
            ),
        )
        AdjustmentCatalog.All.forEach { spec ->
            assertEquals(spec.params.map { it.key }.distinct(), spec.params.map { it.key }, spec.id)
            spec.params.filterIsInstance<NumberParam>().forEach { assertTrue(it.default in it.min..it.max, "${spec.id}.${it.key}") }
            spec.params.filterIsInstance<ChoiceParam>().forEach { p -> assertTrue(p.options.any { it.value == p.default }, "${spec.id}.${p.key}") }
            spec.params.mapNotNull {
                it.showIf
            }.forEach { cond -> assertTrue(spec.params.any { it.key == cond.key }, "${spec.id} showIf ${cond.key}") }
            spec.presets.forEach { preset ->
                assertTrue(preset.values.keys.all { k -> spec.params.any { it.key == k } }, "${spec.id} preset ${preset.name}")
            }
            assertEquals(spec.defaults(), spec.normalize(emptyMap()))
        }
    }

    @Test
    fun normalizeClampsDropsAndRepairs() {
        val spec = AdjustmentCatalog["photoFilter"]!!
        val v = spec.normalize(
            mapOf(
                "density" to ParamValue.Num(900f),
                "filter" to ParamValue.Choice("nope"),
                "preserveLuminosity" to ParamValue.Num(1f), // wrong type
                "stray" to ParamValue.Bool(true),
            ),
        )
        assertEquals(ParamValue.Num(100f), v["density"])
        assertEquals(ParamValue.Choice("warming85"), v["filter"])
        assertEquals(ParamValue.Bool(true), v["preserveLuminosity"])
        assertEquals(null, v["stray"])
    }

    @Test
    fun showIfHidesDependentParams() {
        val spec = AdjustmentCatalog["blackWhite"]!!
        val off = spec.defaults()
        assertTrue(spec.visible(off).none { it.key == "tintColor" })
        assertTrue(spec.visible(off + ("tint" to ParamValue.Bool(true))).any { it.key == "tintColor" })
    }

    @Test
    fun specsAndValuesSerialize() {
        AdjustmentCatalog.All.forEach { spec ->
            assertEquals(spec, json.decodeFromString(AdjustmentSpec.serializer(), json.encodeToString(AdjustmentSpec.serializer(), spec)))
        }
    }

    @Test
    fun persistedParamValuesUseStableGoldenTypeNames() {
        val values = listOf(
            ParamValue.Num(1.25f) to "num",
            ParamValue.Choice("softLight") to "choice",
            ParamValue.Bool(true) to "bool",
            ParamValue.Color(RgbaColor(0.1f, 0.2f, 0.3f, 0.4f)) to "color",
            ParamValue.CurveValue(Curve.Identity) to "curve",
            ParamValue.CurveChannelsValue(
                CurveChannels(
                    rgb = Curve.Identity.withPoint(CurvePoint(0.5f, 0.8f)).first,
                    red = Curve.Identity.withPoint(CurvePoint(0.4f, 0.7f)).first,
                    green = Curve.Identity,
                    blue = Curve.Identity.withPoint(CurvePoint(0.6f, 0.3f)).first,
                ),
            ) to "curveChannels",
            ParamValue.LevelsValue(Levels()) to "levels",
            ParamValue.HueSatValue(HueSaturation()) to "hueSat",
            ParamValue.BalanceValue(ColorBalance()) to "balance",
            ParamValue.GradientValue(ColorGradient.BlackToWhite) to "gradient",
        )

        values.forEach { (value, expectedType) ->
            val encoded = json.encodeToString(ParamValue.serializer(), value)
            assertEquals(expectedType, json.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals(value, json.decodeFromString(ParamValue.serializer(), encoded))
        }
    }

    @Test
    fun legacyCurveJsonDecodesAndNormalizesToRgbWithIdentityChannels() {
        val legacyJson = """{"type":"curve","v":{"points":[{"x":0.0,"y":0.0},{"x":0.5,"y":0.9},{"x":1.0,"y":1.0}]}}"""
        val legacy = json.decodeFromString<ParamValue>(legacyJson) as ParamValue.CurveValue
        val normalized = AdjustmentCatalog["curves"]!!.normalize(mapOf("curve" to legacy))["curve"] as ParamValue.CurveChannelsValue

        assertEquals(legacy.v, normalized.v.rgb)
        assertEquals(Curve.Identity, normalized.v.red)
        assertEquals(Curve.Identity, normalized.v.green)
        assertEquals(Curve.Identity, normalized.v.blue)

        val encoded = json.encodeToString(ParamValue.serializer(), normalized)
        assertEquals("curveChannels", json.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(normalized, json.decodeFromString(ParamValue.serializer(), encoded))
    }

    @Test
    fun channelCurveValueMatchesStableJsonGolden() {
        val value = ParamValue.CurveChannelsValue(CurveChannels())
        val expected =
            """{"type":"curveChannels","v":{"rgb":{"points":[{"x":0.0,"y":0.0},{"x":1.0,"y":1.0}]},"red":{"points":[{"x":0.0,"y":0.0},{"x":1.0,"y":1.0}]},"green":{"points":[{"x":0.0,"y":0.0},{"x":1.0,"y":1.0}]},"blue":{"points":[{"x":0.0,"y":0.0},{"x":1.0,"y":1.0}]}}}"""

        assertEquals(expected, json.encodeToString(ParamValue.serializer(), value))
        assertEquals(value, json.decodeFromString(ParamValue.serializer(), expected))
    }

    @Test
    fun adjustmentSpecsUseStableGoldenTypeNames() {
        val variants = listOf(
            NumberParam("n", "Number", 0f, 1f, 0f) to "number",
            ChoiceParam("c", "Choice", emptyList(), "") to "choice",
            ToggleParam("t", "Toggle") to "toggle",
            ColorParam("color", "Color", RgbaColor.Black) to "color",
            EditorParam("editor", "Editor", EditorParam.Editor.CURVE) to "editor",
        )
        variants.forEach { (spec, expectedType) ->
            val encoded = json.encodeToString(ParamSpec.serializer(), spec)
            assertEquals(expectedType, json.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals(spec, json.decodeFromString(ParamSpec.serializer(), encoded))
        }
    }

    @Test
    fun selectionInfoUsesStableGoldenTypeNames() {
        val values = listOf(
            SelectionInfo.None to "none",
            SelectionInfo.Active(DocRect(1, 2, 3, 4), feathered = true) to "active",
        )
        values.forEach { (value, expectedType) ->
            val encoded = json.encodeToString(SelectionInfo.serializer(), value)
            assertEquals(expectedType, json.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals(value, json.decodeFromString(SelectionInfo.serializer(), encoded))
        }
    }
}
