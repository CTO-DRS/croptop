package app.croptop.core.io

import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BlendMode
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerLocks
import app.croptop.core.model.LayerMaskInfo
import app.croptop.core.model.LayerNode
import app.croptop.core.model.LayerTree
import app.croptop.core.model.ParamValue
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorSubpath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CroptopManifestTest {
    private val background = LayerNode(LayerId(1), "Background", LayerKind.PIXEL, locks = LayerLocks(pixels = true))
    private val curves = LayerNode(
        LayerId(2),
        "Curves",
        LayerKind.ADJUSTMENT,
        adjustment = AdjustmentInfo("levels", mapOf("gamma" to ParamValue.Num(1.2f))),
    )
    private val painted = LayerNode(
        LayerId(4),
        "Paint",
        LayerKind.PIXEL,
        opacity = 0.5f,
        blendMode = BlendMode.MULTIPLY,
        clipped = true,
        mask = LayerMaskInfo(density = 0.8f, feather = 2f),
        bounds = DocRect(10, 20, 110, 220),
    )
    private val group = LayerNode(LayerId(3), "Group", LayerKind.GROUP, children = listOf(painted, curves))
    private val tree = LayerTree(listOf(group, background), active = setOf(LayerId(4), LayerId(1)), primary = LayerId(4))
    private val uuids = mapOf(1L to "uuid-bg", 2L to "uuid-curves", 3L to "uuid-group", 4L to "uuid-paint")
    private val canvas = CroptopCanvas(
        width = 4000,
        height = 3000,
        depth = ColorDepth.U16,
        profile = CroptopProfile.of(ColorProfileRef.Embedded("Camera RGB", "abc123")),
        resolutionPpi = 300f,
    )

    @Test
    fun roundTripsEditableVectorPathsOnShapeLayers() {
        val path = VectorPathDraft(
            id = VectorPathId(8),
            name = "Editable rectangle",
            subpaths = listOf(
                VectorSubpath(
                    anchors = listOf(
                        PathAnchor(PathAnchorId(21), DocPoint(10f, 20f)),
                        PathAnchor(PathAnchorId(22), DocPoint(110f, 20f)),
                        PathAnchor(PathAnchorId(23), DocPoint(110f, 90f)),
                    ),
                    closed = true,
                ),
            ),
        )
        val shape = LayerNode(LayerId(9), "Shape", LayerKind.SHAPE, vectorPaths = listOf(path))
        val sourceTree = LayerTree(listOf(shape), active = setOf(shape.id), primary = shape.id)
        val source = CroptopManifest.fromLayerTree(
            tree = sourceTree,
            canvas = canvas,
            uuidOf = { "shape-${it.value}" },
            pixelFormatOf = { null },
        )

        val decoded = CroptopManifestCodec.decode(CroptopManifestCodec.encode(source)).manifest
        assertEquals(path, decoded.layers.single().vectorPaths.single())
        val (restored, _) = decoded.toLayerTree { LayerId(99) }
        assertEquals(path, restored.layers.single().vectorPaths.single())
    }

    private fun manifest() = CroptopManifest.fromLayerTree(
        tree = tree,
        canvas = canvas,
        uuidOf = { uuids.getValue(it.value) },
        pixelFormatOf = { if (it.value == 1L || it.value == 4L) CroptopPixelFormat.RGBA16 else null },
        appVersion = "1.0.0",
        name = "Poster",
        guides = listOf(Guide(GuideAxis.VERTICAL, 12.5f)),
        metadata = mapOf("author" to "me"),
    )

    @Test
    fun roundTripsTheLayerTreeWithFreshRuntimeIds() {
        val decoded = CroptopManifestCodec.decode(CroptopManifestCodec.encode(manifest())).manifest
        assertEquals(manifest(), decoded)
        var next = 100L
        val (restored, ids) = decoded.toLayerTree { LayerId(next++) }
        assertEquals(mapOf("uuid-group" to 100L, "uuid-paint" to 101L, "uuid-curves" to 102L, "uuid-bg" to 103L), ids.mapValues { it.value.value })
        // Same tree once runtime ids are mapped back.
        fun strip(node: LayerNode): LayerNode = node.copy(id = LayerId(0), children = node.children.map(::strip))
        assertEquals(tree.layers.map(::strip), restored.layers.map(::strip))
        assertEquals(setOf(LayerId(101), LayerId(103)), restored.active)
        assertEquals(LayerId(101), restored.primary)
        assertEquals(listOf("uuid-paint" to CroptopPixelFormat.RGBA16, "uuid-bg" to CroptopPixelFormat.RGBA16), decoded.pixelLayers())
        assertEquals(ColorProfileRef.Embedded("Camera RGB", "abc123"), decoded.canvas.profile.toModel())
    }

    @Test
    fun maskDefaultCoverageSurvivesManifestRoundTripAndIsValidated() {
        val maskedTree = LayerTree(
            layers = listOf(painted),
            primary = painted.id,
            active = setOf(painted.id),
        )
        val source = CroptopManifest.fromLayerTree(
            tree = maskedTree,
            canvas = canvas,
            uuidOf = { "masked-${it.value}" },
            pixelFormatOf = { CroptopPixelFormat.RGBA8 },
            maskDefaultOf = { 0f },
            maskPayloadPresentOf = { true },
        )
        val encoded = CroptopManifestCodec.encode(source)
        val decoded = CroptopManifestCodec.decode(encoded).manifest.layers.single()
        assertEquals(0f, decoded.maskDefault)
        assertTrue(decoded.maskPayloadPresent)
        assertFailsWith<CroptopManifestException> {
            CroptopManifestCodec.decode(encoded.decodeToString().replace("\"maskDefault\":0", "\"maskDefault\":2").encodeToByteArray())
        }
    }

    @Test
    fun namedSelectionChannelsRoundTripAndOlderManifestsDefaultToNone() {
        val source = manifest().copy(
            selectionChannels = listOf(CroptopSelectionChannel("Portrait", "12345678-1234-1234-1234-123456789abc")),
        )
        assertEquals(source, CroptopManifestCodec.decode(CroptopManifestCodec.encode(source)).manifest)
        val old = CroptopManifestCodec.decode(
            """{"formatVersion":1,"canvas":{"width":10,"height":20}}""".encodeToByteArray(),
        ).manifest
        assertTrue(old.selectionChannels.isEmpty())
    }

    @Test
    fun rejectsInvalidNamedSelectionChannelMetadata() {
        val valid = manifest().copy(
            selectionChannels = listOf(CroptopSelectionChannel("Portrait", "12345678-1234-1234-1234-123456789abc")),
        )
        assertFailsWith<CroptopManifestException> {
            CroptopManifestCodec.encode(valid.copy(selectionChannels = listOf(CroptopSelectionChannel(" ", "12345678-1234-1234-1234-123456789abc"))))
        }
        assertFailsWith<CroptopManifestException> {
            CroptopManifestCodec.encode(valid.copy(selectionChannels = valid.selectionChannels + valid.selectionChannels))
        }
        assertFailsWith<CroptopManifestException> {
            CroptopManifestCodec.encode(valid.copy(selectionChannels = listOf(CroptopSelectionChannel("Portrait", "../mask"))))
        }
    }

    @Test
    fun manifestRejectsMorePixelStoresThanTheArchiveJniLimit() {
        val layers = (1..CroptopManifest.MAX_LAYER_STORES + 1).map { index ->
            LayerNode(LayerId(index.toLong()), "Layer $index", LayerKind.PIXEL)
        }
        val tooMany = CroptopManifest.fromLayerTree(
            tree = LayerTree(layers),
            canvas = canvas,
            uuidOf = { "layer-${it.value}" },
            pixelFormatOf = { CroptopPixelFormat.RGBA8 },
        )
        assertFailsWith<CroptopManifestException> { CroptopManifestCodec.encode(tooMany) }
    }

    @Test
    fun wireFormatUsesStableNamesNotKotlinClassNames() {
        val text = CroptopManifestCodec.encode(manifest()).decodeToString()
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("1", root.getValue("formatVersion").jsonPrimitive.content)
        assertEquals("embedded", root.getValue("canvas").jsonObject.getValue("profile").jsonObject.getValue("kind").jsonPrimitive.content)
        assertTrue("app.croptop.core.model.ColorProfileRef" !in text)
    }

    @Test
    fun carriesUnknownKeysForwardAtEveryLevel() {
        val saved = Json.parseToJsonElement(CroptopManifestCodec.encode(manifest()).decodeToString()).jsonObject
        // Simulate a newer writer adding fields at the root, inside canvas, and on one nested layer.
        val future = JsonObject(
            saved + mapOf(
                "futureRoot" to Json.parseToJsonElement("""{"x":[1,2,3]}"""),
                "canvas" to JsonObject(saved.getValue("canvas").jsonObject + ("futureCanvas" to Json.parseToJsonElement("true"))),
                "layers" to Json.parseToJsonElement(
                    saved.getValue("layers").toString().replace(
                        "\"uuid\":\"uuid-paint\"",
                        "\"uuid\":\"uuid-paint\",\"futureEffect\":{\"glow\":4}",
                    ),
                ),
            ),
        )
        val decoded = CroptopManifestCodec.decode(future.toString().encodeToByteArray())
        assertEquals(manifest(), decoded.manifest)

        // Edit through this version: rename a layer and delete the background, then save.
        val edited = decoded.manifest.copy(
            layers = decoded.manifest.layers.take(1).map { g ->
                g.copy(children = g.children.map { if (it.uuid == "uuid-paint") it.copy(name = "Renamed") else it })
            },
            activeLayers = listOf("uuid-paint"),
        )
        val rewritten = Json.parseToJsonElement(CroptopManifestCodec.encode(edited, decoded.original).decodeToString()).jsonObject
        assertEquals("""{"x":[1,2,3]}""", rewritten.getValue("futureRoot").toString())
        assertEquals("true", rewritten.getValue("canvas").jsonObject.getValue("futureCanvas").toString())
        val paint = rewritten.getValue("layers").jsonArray[0].jsonObject.getValue("children").jsonArray[0].jsonObject
        assertEquals("Renamed", paint.getValue("name").jsonPrimitive.content)
        assertEquals("""{"glow":4}""", paint.getValue("futureEffect").toString())
        assertEquals(1, rewritten.getValue("layers").jsonArray.size, "deleted layers are not resurrected")
    }

    @Test
    fun rejectsNewerVersionsAndMalformedManifests() {
        fun decode(text: String) = CroptopManifestCodec.decode(text.encodeToByteArray())
        val valid = CroptopManifestCodec.encode(manifest()).decodeToString()
        assertFailsWith<CroptopManifestException> { decode(valid.replace("\"formatVersion\":1", "\"formatVersion\":2")) }
        assertFailsWith<CroptopManifestException> { decode(valid.replace("\"formatVersion\":1,", "")) }
        assertFailsWith<CroptopManifestException> { decode("[]") }
        assertFailsWith<CroptopManifestException> { decode("{not json") }
        assertFailsWith<CroptopManifestException> { CroptopManifestCodec.decode(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)) }
        assertFailsWith<CroptopManifestException> { decode(valid.replace("\"uuid-bg\"", "\"uuid-paint\"")) }
        assertFailsWith<CroptopManifestException> { decode(valid.replace("\"uuid\":\"uuid-bg\"", "\"uuid\":\"../x\"")) }
        assertFailsWith<CroptopManifestException> { decode(valid.replace("\"width\":4000", "\"width\":0")) }
        assertFailsWith<CroptopManifestException> { decode(valid.replace("\"kind\":\"PIXEL\"", "\"kind\":\"HOLOGRAM\"")) }
        assertFailsWith<CroptopManifestException> {
            CroptopManifestCodec.encode(manifest().copy(primaryLayer = "missing"))
        }
    }

    @Test
    fun minimalManifestUsesDefaults() {
        val decoded = CroptopManifestCodec.decode("""{"formatVersion":1,"canvas":{"width":10,"height":20}}""".encodeToByteArray()).manifest
        assertEquals(ColorDepth.U8, decoded.canvas.depth)
        assertEquals(ColorProfileRef.DisplayP3, decoded.canvas.profile.toModel())
        assertTrue(decoded.layers.isEmpty())
        assertNull(decoded.primaryLayer)
        assertTrue(CroptopManifest.newLayerUuid().matches(Regex("[0-9a-f-]{36}")))
    }

    @Test
    fun frozenVersionOneFixtureManifestStillDecodes() {
        // Same bytes as the native frozen fixture (core/engine/src/main/cpp/croptop_v1_fixture.inc).
        val fixture = """{"formatVersion":1,"appVersion":"fixture-1","name":"Fixture","canvas":{"width":260,"height":12},""" +
            """"layers":[{"uuid":"layer-rgba","name":"Pixels","kind":"PIXEL","pixelFormat":"RGBA8","blendMode":"NORMAL"},""" +
            """{"uuid":"layer-a16","name":"Mask","kind":"PIXEL","pixelFormat":"A16","blendMode":"NORMAL"}],"futureKey":{"keep":true}}"""
        val decoded = CroptopManifestCodec.decode(fixture.encodeToByteArray())
        assertEquals(260, decoded.manifest.canvas.width)
        assertEquals(
            listOf("layer-rgba" to CroptopPixelFormat.RGBA8, "layer-a16" to CroptopPixelFormat.A16),
            decoded.manifest.pixelLayers(),
        )
        val rewritten = Json.parseToJsonElement(CroptopManifestCodec.encode(decoded.manifest, decoded.original).decodeToString()).jsonObject
        assertEquals("""{"keep":true}""", rewritten.getValue("futureKey").toString())
    }
}
