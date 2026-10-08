// `.croptop` v1 manifest. The native container stores these bytes verbatim.
package app.croptop.core.io

import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BlendIfSettings
import app.croptop.core.model.BlendMode
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocRect
import app.croptop.core.model.GridSettings
import app.croptop.core.model.Guide
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerLocks
import app.croptop.core.model.LayerMaskInfo
import app.croptop.core.model.LayerNode
import app.croptop.core.model.LayerTree
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.WorkingSpace
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Pixel storage of a layer's tiles; matches the native `tiles/<uuid>/info` format code. */
@Serializable
enum class CroptopPixelFormat { RGBA8, RGBA16, RGBA32F, A8, A16 }

/** Stable wire form of the document colour profile, independent of Kotlin class names. */
@Serializable
data class CroptopProfile(
    /** "builtin" or "embedded". */
    val kind: String,
    val space: WorkingSpace? = null,
    val linear: Boolean = false,
    val name: String? = null,
    /** Identifies the bytes of `icc/working.icc` for embedded profiles. */
    val hash: String? = null,
) {
    fun toModel(): ColorProfileRef = when (kind) {
        KIND_BUILTIN -> ColorProfileRef.BuiltIn(space ?: throw CroptopManifestException("builtin profile without space"), linear)
        KIND_EMBEDDED -> ColorProfileRef.Embedded(name.orEmpty(), hash ?: throw CroptopManifestException("embedded profile without hash"))
        else -> throw CroptopManifestException("unknown profile kind '$kind'")
    }

    companion object {
        const val KIND_BUILTIN = "builtin"
        const val KIND_EMBEDDED = "embedded"

        fun of(profile: ColorProfileRef): CroptopProfile = when (profile) {
            is ColorProfileRef.BuiltIn -> CroptopProfile(KIND_BUILTIN, space = profile.space, linear = profile.linear)
            is ColorProfileRef.Embedded -> CroptopProfile(KIND_EMBEDDED, name = profile.displayName, hash = profile.hash)
        }
    }
}

@Serializable
data class CroptopCanvas(
    val width: Int,
    val height: Int,
    val depth: ColorDepth = ColorDepth.U8,
    val profile: CroptopProfile = CroptopProfile.of(ColorProfileRef.DisplayP3),
    val resolutionPpi: Float = 72f,
)

/** A named selection's A16 payload, stored outside the layer tree but alongside its tile data. */
@Serializable
data class CroptopSelectionChannel(
    val name: String,
    val payloadKey: String,
)

/** One node of the layer tree, identified by a persistent format-level [uuid] instead of a runtime [LayerId]. */
@Serializable
data class CroptopLayer(
    val uuid: String,
    val name: String,
    val kind: LayerKind,
    /** Set when the layer has a `tiles/<uuid>/` payload in the container. */
    val pixelFormat: CroptopPixelFormat? = null,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val fillOpacity: Float = 1f,
    val blendMode: BlendMode,
    val locks: LayerLocks = LayerLocks.None,
    val clipped: Boolean = false,
    val blendIf: BlendIfSettings = BlendIfSettings(),
    val mask: LayerMaskInfo? = null,
    /** Coverage for mask tiles with no stored pixels (0 = hide, 1 = reveal). */
    val maskDefault: Float = 1f,
    /** True when this archive must contain the layer's private A16 mask payload. */
    val maskPayloadPresent: Boolean = false,
    val hasVectorMask: Boolean = false,
    val hasStyles: Boolean = false,
    val bounds: DocRect? = null,
    val adjustment: AdjustmentInfo? = null,
    /** Editable shape geometry; never replaced by its rebuildable raster cache. */
    val vectorPaths: List<VectorPathDraft> = emptyList(),
    /** Editable text content of text layers; their pixels are re-rendered on open. */
    val text: app.croptop.core.model.TextLayerInfo? = null,
    val styles: app.croptop.core.model.LayerStyles? = null,
    /** Smart object: its source pixels are stored in the archive under [app.croptop.core.model.SmartObjectInfo.sourceKey]. */
    val smartObject: app.croptop.core.model.SmartObjectInfo? = null,
    val fill: app.croptop.core.model.FillLayerInfo? = null,
    val hiddenPixels: app.croptop.core.model.HiddenPixelsInfo? = null,
    /** Top-most child first, as in [LayerTree]. */
    val children: List<CroptopLayer> = emptyList(),
)

@Serializable
data class CroptopManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String = "",
    val name: String = "",
    val canvas: CroptopCanvas,
    /** Top-most first, as in [LayerTree.layers]. */
    val layers: List<CroptopLayer> = emptyList(),
    val activeLayers: List<String> = emptyList(),
    val primaryLayer: String? = null,
    val guides: List<Guide> = emptyList(),
    /** Grid spacing/subdivisions saved with the document; null = app default. */
    val grid: GridSettings? = null,
    /** Named A16 selections saved with the document; missing field means an older file with no channels. */
    val selectionChannels: List<CroptopSelectionChannel> = emptyList(),
    /** Free-form document metadata (author, source file, …). */
    val metadata: Map<String, String> = emptyMap(),
) {
    /** Depth-first, panel order, including group children. */
    fun flatten(): List<CroptopLayer> = buildList {
        fun walk(nodes: List<CroptopLayer>) {
            nodes.forEach {
                add(it)
                walk(it.children)
            }
        }
        walk(layers)
    }

    /** Layers that carry a tile payload, with the format the native loader must allocate. */
    fun pixelLayers(): List<Pair<String, CroptopPixelFormat>> = flatten().mapNotNull { layer ->
        layer.pixelFormat?.let { layer.uuid to it }
    }

    /**
     * Rebuilds the runtime [LayerTree], assigning fresh ids from [newId] in panel order.
     * Returns the tree and the uuid → id mapping the loader uses to attach tile stores.
     */
    fun toLayerTree(newId: () -> LayerId): Pair<LayerTree, Map<String, LayerId>> {
        val ids = LinkedHashMap<String, LayerId>()
        fun convert(layer: CroptopLayer): LayerNode {
            val id = newId()
            ids[layer.uuid] = id
            return LayerNode(
                id = id,
                name = layer.name,
                kind = layer.kind,
                visible = layer.visible,
                opacity = layer.opacity,
                fillOpacity = layer.fillOpacity,
                blendMode = layer.blendMode,
                locks = layer.locks,
                clipped = layer.clipped,
                blendIf = layer.blendIf,
                mask = layer.mask,
                hasVectorMask = layer.hasVectorMask,
                hasStyles = layer.hasStyles,
                children = layer.children.map(::convert),
                bounds = layer.bounds,
                adjustment = layer.adjustment,
                vectorPaths = layer.vectorPaths,
                text = layer.text,
                styles = layer.styles,
                smartObject = layer.smartObject,
                fill = layer.fill,
                hiddenPixels = layer.hiddenPixels,
            )
        }
        val layers = this.layers.map(::convert)
        val tree = LayerTree(
            layers = layers,
            active = activeLayers.mapNotNull(ids::get).toSet(),
            primary = primaryLayer?.let(ids::get),
        )
        return tree to ids
    }

    companion object {
        /** Bump for changes older readers cannot ignore; additive fields keep the version. */
        const val FORMAT_VERSION = 1
        const val MAX_LAYER_STORES = 500
        const val MAX_SELECTION_CHANNELS = 128

        /** Stable archive key for a layer's optional A16 mask payload. */
        fun maskPayloadUuid(layerUuid: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(layerUuid.toByteArray(Charsets.UTF_8))
            return "mask-" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /**
         * Snapshot of a runtime tree. [uuidOf] must return the layer's persistent uuid (generate one
         * with [newLayerUuid] the first time a layer is saved); [pixelFormatOf] returns null for
         * layers without a tile payload.
         */
        fun fromLayerTree(
            tree: LayerTree,
            canvas: CroptopCanvas,
            uuidOf: (LayerId) -> String,
            pixelFormatOf: (LayerId) -> CroptopPixelFormat?,
            maskDefaultOf: (LayerId) -> Float = { 1f },
            maskPayloadPresentOf: (LayerId) -> Boolean = { false },
            appVersion: String = "",
            name: String = "",
            guides: List<Guide> = emptyList(),
            metadata: Map<String, String> = emptyMap(),
            grid: GridSettings? = null,
            selectionChannels: List<CroptopSelectionChannel> = emptyList(),
        ): CroptopManifest {
            fun convert(node: LayerNode): CroptopLayer = CroptopLayer(
                uuid = uuidOf(node.id),
                name = node.name,
                kind = node.kind,
                pixelFormat = pixelFormatOf(node.id),
                visible = node.visible,
                opacity = node.opacity,
                fillOpacity = node.fillOpacity,
                blendMode = node.blendMode,
                locks = node.locks,
                clipped = node.clipped,
                blendIf = node.blendIf,
                mask = node.mask,
                maskDefault = maskDefaultOf(node.id),
                maskPayloadPresent = maskPayloadPresentOf(node.id),
                hasVectorMask = node.hasVectorMask,
                hasStyles = node.hasStyles,
                bounds = node.bounds,
                adjustment = node.adjustment,
                vectorPaths = node.vectorPaths,
                text = node.text,
                styles = node.styles,
                smartObject = node.smartObject,
                fill = node.fill,
                hiddenPixels = node.hiddenPixels,
                children = node.children.map(::convert),
            )
            return CroptopManifest(
                appVersion = appVersion,
                name = name,
                canvas = canvas,
                layers = tree.layers.map(::convert),
                activeLayers = tree.flatten().filter { it.id in tree.active }.map { uuidOf(it.id) },
                primaryLayer = tree.primary?.let(uuidOf),
                guides = guides,
                grid = grid,
                selectionChannels = selectionChannels,
                metadata = metadata,
            )
        }

        fun newLayerUuid(): String = java.util.UUID.randomUUID().toString()
    }
}

class CroptopManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A decoded manifest plus its original JSON, kept so a later save can carry unknown keys forward. */
data class DecodedCroptopManifest(val manifest: CroptopManifest, val original: JsonObject)

/**
 * Reads and writes `manifest.json`. Keys this version does not know are ignored on read and carried
 * over on write ([encode] with `original`), at every object level; layer objects are matched by uuid.
 */
object CroptopManifestCodec {
    private const val MAX_BYTES = 64 * 1024 * 1024
    private val UUID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun decode(bytes: ByteArray): DecodedCroptopManifest {
        if (bytes.size > MAX_BYTES) throw CroptopManifestException("manifest is too large")
        val root = try {
            json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        } catch (e: SerializationException) {
            throw CroptopManifestException("manifest is not valid JSON", e)
        } catch (e: CharacterCodingException) {
            throw CroptopManifestException("manifest is not valid UTF-8", e)
        } ?: throw CroptopManifestException("manifest must be a JSON object")
        val version = (root["formatVersion"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: throw CroptopManifestException("manifest has no formatVersion")
        if (version < 1) throw CroptopManifestException("invalid formatVersion $version")
        if (version > CroptopManifest.FORMAT_VERSION) {
            throw CroptopManifestException("file was saved by a newer CropTop (format $version)")
        }
        val manifest = try {
            json.decodeFromJsonElement<CroptopManifest>(root)
        } catch (e: SerializationException) {
            throw CroptopManifestException("manifest does not match format $version: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CroptopManifestException("manifest has an invalid value: ${e.message}", e)
        }
        validate(manifest)
        return DecodedCroptopManifest(manifest, root)
    }

    fun encode(manifest: CroptopManifest, original: JsonObject? = null): ByteArray {
        validate(manifest)
        val fresh = json.encodeToJsonElement(manifest)
        val merged = if (original == null) fresh else carryUnknown(fresh, original)
        return json.encodeToString(JsonElement.serializer(), merged).encodeToByteArray()
    }

    private fun validate(manifest: CroptopManifest) {
        val canvas = manifest.canvas
        if (canvas.width <= 0 || canvas.height <= 0 || canvas.width > 65_535 || canvas.height > 65_535) {
            throw CroptopManifestException("canvas size ${canvas.width}×${canvas.height} is out of range")
        }
        val layers = manifest.flatten()
        if (manifest.pixelLayers().size > CroptopManifest.MAX_LAYER_STORES) {
            throw CroptopManifestException("project has more than ${CroptopManifest.MAX_LAYER_STORES} pixel layer stores")
        }
        val uuids = HashSet<String>()
        for (layer in layers) {
            if (!UUID_PATTERN.matches(layer.uuid)) throw CroptopManifestException("invalid layer uuid '${layer.uuid}'")
            if (!uuids.add(layer.uuid)) throw CroptopManifestException("duplicate layer uuid ${layer.uuid}")
            if (layer.kind != LayerKind.GROUP && layer.children.isNotEmpty()) {
                throw CroptopManifestException("layer ${layer.uuid} is not a group but has children")
            }
            if (layer.opacity !in 0f..1f || layer.fillOpacity !in 0f..1f) {
                throw CroptopManifestException("layer ${layer.uuid} opacity is out of range")
            }
            if (!layer.maskDefault.isFinite() || layer.maskDefault !in 0f..1f) {
                throw CroptopManifestException("layer ${layer.uuid} mask default is out of range")
            }
            if (layer.maskPayloadPresent && (layer.mask == null || layer.pixelFormat == null)) {
                throw CroptopManifestException("layer ${layer.uuid} requires a mask payload without a stored pixel layer")
            }
            if (layer.vectorPaths.isNotEmpty() && layer.kind != LayerKind.SHAPE) {
                throw CroptopManifestException("layer ${layer.uuid} stores vector paths but is not a shape layer")
            }
            if (layer.vectorPaths.size > 100_000) throw CroptopManifestException("layer ${layer.uuid} has too many vector paths")
            val pathIds = HashSet<Long>()
            val anchorIds = HashSet<Long>()
            var anchorCount = 0
            for (path in layer.vectorPaths) {
                if (path.id.value <= 0L || !pathIds.add(path.id.value)) {
                    throw CroptopManifestException("layer ${layer.uuid} has an invalid or duplicate path id")
                }
                if (path.name.isBlank() || path.name.toByteArray(Charsets.UTF_8).size > 1024) {
                    throw CroptopManifestException("layer ${layer.uuid} has an invalid vector path name")
                }
                if (path.style.normalized() != path.style || path.primitive?.normalized() != path.primitive) {
                    throw CroptopManifestException("layer ${layer.uuid} has non-normalized vector path values")
                }
                for (subpath in path.subpaths) {
                    if (subpath.anchors.isEmpty()) throw CroptopManifestException("layer ${layer.uuid} has an empty vector subpath")
                    anchorCount += subpath.anchors.size
                    if (anchorCount > 100_000) throw CroptopManifestException("layer ${layer.uuid} has too many vector anchors")
                    for (anchor in subpath.anchors) {
                        if (anchor.id.value <= 0L || !anchorIds.add(anchor.id.value)) {
                            throw CroptopManifestException("layer ${layer.uuid} has an invalid or duplicate anchor id")
                        }
                        val points = listOfNotNull(anchor.point, anchor.incoming, anchor.outgoing)
                        if (points.any { !it.x.isFinite() || !it.y.isFinite() || kotlin.math.abs(it.x) > 1e7f || kotlin.math.abs(it.y) > 1e7f }) {
                            throw CroptopManifestException("layer ${layer.uuid} has out-of-range vector geometry")
                        }
                    }
                }
            }
        }
        if (manifest.selectionChannels.size > CroptopManifest.MAX_SELECTION_CHANNELS) {
            throw CroptopManifestException("project has more than ${CroptopManifest.MAX_SELECTION_CHANNELS} selection channels")
        }
        val channelNames = HashSet<String>()
        for (channel in manifest.selectionChannels) {
            if (channel.name.isBlank() || channel.name.length > 255) {
                throw CroptopManifestException("selection channel name is empty or too long")
            }
            if (!channelNames.add(channel.name)) throw CroptopManifestException("duplicate selection channel name '${channel.name}'")
            if (!UUID_PATTERN.matches(channel.payloadKey)) throw CroptopManifestException("invalid selection channel payload key")
            if (!uuids.add(channel.payloadKey)) throw CroptopManifestException("duplicate selection channel payload key")
        }
        (manifest.activeLayers + listOfNotNull(manifest.primaryLayer)).firstOrNull { it !in uuids }?.let {
            throw CroptopManifestException("active layer $it does not exist")
        }
        layers.mapNotNull { it.smartObject?.filterMask }.firstOrNull { !UUID_PATTERN.matches(it.key) || it.key in uuids }?.let {
            throw CroptopManifestException("invalid smart filter mask key")
        }
        layers.mapNotNull { it.smartObject?.raw }.firstOrNull { !UUID_PATTERN.matches(it.fileKey) }?.let {
            throw CroptopManifestException("invalid RAW source key")
        }
        layers.mapNotNull { it.smartObject }.firstOrNull { !UUID_PATTERN.matches(it.sourceKey) || it.sourceKey in uuids }?.let {
            throw CroptopManifestException("invalid smart object source '${it.sourceKey}'")
        }
        layers.mapNotNull { it.hiddenPixels }.firstOrNull {
            !UUID_PATTERN.matches(it.sourceKey) || it.sourceKey in uuids || it.sourceWidth !in 1..65_535 || it.sourceHeight !in 1..65_535
        }?.let { throw CroptopManifestException("invalid hidden pixel source '${it.sourceKey}'") }
        if (manifest.guides.size > 10_000 || manifest.guides.any { !it.position.isFinite() }) {
            throw CroptopManifestException("manifest guides are invalid")
        }
    }

    /**
     * Returns [fresh] with keys that exist only in [original] added back. Objects merge recursively;
     * arrays of objects with a "uuid" key merge element-wise by uuid (deleted layers stay deleted);
     * other arrays and all primitives take the fresh value.
     */
    private fun carryUnknown(fresh: JsonElement, original: JsonElement): JsonElement = when {
        fresh is JsonObject && original is JsonObject -> JsonObject(
            buildMap {
                fresh.forEach { (key, value) -> put(key, original[key]?.let { carryUnknown(value, it) } ?: value) }
                original.forEach { (key, value) -> if (key !in fresh) put(key, value) }
            },
        )

        fresh is JsonArray && original is JsonArray -> {
            val byUuid = original.filterIsInstance<JsonObject>().mapNotNull { element ->
                (element["uuid"] as? JsonPrimitive)?.contentOrNull?.let { it to element }
            }.toMap()
            if (byUuid.isEmpty()) {
                fresh
            } else {
                JsonArray(
                    fresh.map { element ->
                        val uuid = (element as? JsonObject)?.get("uuid")?.jsonPrimitive?.contentOrNull
                        uuid?.let(byUuid::get)?.let { carryUnknown(element, it) } ?: element
                    },
                )
            }
        }

        else -> fresh
    }
}
