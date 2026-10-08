package app.croptop.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LayerKind { PIXEL, ADJUSTMENT, FILL, TEXT, SHAPE, GROUP, SMART_OBJECT }

@Serializable
data class LayerLocks(
    val transparency: Boolean = false,
    val pixels: Boolean = false,
    val position: Boolean = false,
    val artboard: Boolean = false,
) {
    val all: Boolean get() = transparency && pixels && position
    val any: Boolean get() = transparency || pixels || position

    companion object {
        val None = LayerLocks()
        val All = LayerLocks(transparency = true, pixels = true, position = true)
    }
}

@Serializable
data class LayerMaskInfo(
    val enabled: Boolean = true,
    val linked: Boolean = true,
    /** 0..1, mask properties. */
    val density: Float = 1f,
    /** Feather radius in document pixels. */
    val feather: Float = 0f,
)

/**
 * One Blend If slider pair on 0..255: pixels darker than [blackLo] are hidden, fully shown from [blackHi]; pixels brighter than
 * [whiteHi] are hidden, fully shown up to [whiteLo]. Splitting a pair (lo != hi) gives a soft ramp.
 */
@Serializable
data class BlendIfRange(
    val blackLo: Int = 0,
    val blackHi: Int = 0,
    val whiteLo: Int = 255,
    val whiteHi: Int = 255,
) {
    init {
        require(blackLo in 0..255 && blackHi in 0..255 && whiteLo in 0..255 && whiteHi in 0..255) { "Blend If values must be 0..255" }
        require(blackLo <= blackHi && whiteLo <= whiteHi) { "Blend If split points must not cross" }
        require(blackHi <= whiteLo) { "Blend If black point must not pass the white point" }
    }

    val isDefault: Boolean get() = this == BlendIfRange()
}

/** The channel a Blend If slider pair reads. */
enum class BlendIfChannel(val label: String) {
    GRAY("Gray"),
    RED("Red"),
    GREEN("Green"),
    BLUE("Blue"),
}

/** One channel's Blend If sliders: [self] reads this layer's values, [under] the layers beneath. */
@Serializable
data class BlendIfPair(
    val self: BlendIfRange = BlendIfRange(),
    val under: BlendIfRange = BlendIfRange(),
) {
    val isDefault: Boolean get() = self.isDefault && under.isDefault
}

/**
 * Layer ▸ Blending Options ▸ Blend If. [self] and [under] are the Gray channel (luminance); [red], [green] and [blue] add a pair
 * each. A pixel is shown in proportion to all of them at once, like Photoshop.
 */
@Serializable
data class BlendIfSettings(
    val self: BlendIfRange = BlendIfRange(),
    val under: BlendIfRange = BlendIfRange(),
    val red: BlendIfPair = BlendIfPair(),
    val green: BlendIfPair = BlendIfPair(),
    val blue: BlendIfPair = BlendIfPair(),
) {
    val isDefault: Boolean get() = self.isDefault && under.isDefault && red.isDefault && green.isDefault && blue.isDefault

    fun pair(channel: BlendIfChannel): BlendIfPair = when (channel) {
        BlendIfChannel.GRAY -> BlendIfPair(self, under)
        BlendIfChannel.RED -> red
        BlendIfChannel.GREEN -> green
        BlendIfChannel.BLUE -> blue
    }

    fun with(channel: BlendIfChannel, pair: BlendIfPair): BlendIfSettings = when (channel) {
        BlendIfChannel.GRAY -> copy(self = pair.self, under = pair.under)
        BlendIfChannel.RED -> copy(red = pair)
        BlendIfChannel.GREEN -> copy(green = pair)
        BlendIfChannel.BLUE -> copy(blue = pair)
    }

    /** The 32-value layout the engine and PSD blending ranges use: gray, red, green, blue, each this layer's four then the underlying four. */
    fun toInts(): IntArray {
        val out = IntArray(INTS)
        var at = 0
        for (channel in BlendIfChannel.entries) {
            val pair = pair(channel)
            for (range in listOf(pair.self, pair.under)) {
                out[at++] = range.blackLo
                out[at++] = range.blackHi
                out[at++] = range.whiteLo
                out[at++] = range.whiteHi
            }
        }
        return out
    }

    companion object {
        const val INTS = 32

        /**
         * Reads the layout of [toInts] (or just its first eight values, gray only); null when a range is invalid, because Photoshop
         * files can hold crossing values this model rejects.
         */
        fun fromInts(values: IntArray): BlendIfSettings? {
            if (values.size != 8 && values.size != INTS) return null
            return runCatching {
                fun range(at: Int) = BlendIfRange(values[at], values[at + 1], values[at + 2], values[at + 3])

                fun pair(group: Int) = if (values.size == INTS) BlendIfPair(range(8 * group), range(8 * group + 4)) else BlendIfPair()
                BlendIfSettings(range(0), range(4), pair(1), pair(2), pair(3))
            }.getOrNull()
        }
    }
}

/**
 * Immutable snapshot of one layer for the UI. Pixels never live here —
 * thumbnails come from the engine.
 */
@Serializable
data class LayerNode(
    val id: LayerId,
    val name: String,
    val kind: LayerKind,
    val visible: Boolean = true,
    /** 0..1 */
    val opacity: Float = 1f,
    /** 0..1, "Fill" in Photoshop terms — excludes layer styles. */
    val fillOpacity: Float = 1f,
    val blendMode: BlendMode = if (kind == LayerKind.GROUP) BlendMode.PASS_THROUGH else BlendMode.NORMAL,
    val locks: LayerLocks = LayerLocks.None,
    /** Clipped to the layer below (clipping mask). */
    val clipped: Boolean = false,
    /** Blending Options ▸ Blend If sliders; default keeps every pixel. */
    val blendIf: BlendIfSettings = BlendIfSettings(),
    val mask: LayerMaskInfo? = null,
    val hasVectorMask: Boolean = false,
    val hasStyles: Boolean = false,
    /** Only non-empty for [LayerKind.GROUP]. Top-most child first. */
    val children: List<LayerNode> = emptyList(),
    /** Content bounds in document pixels (Move handles, hit-testing); null = unknown or the full canvas. */
    val bounds: DocRect? = null,
    /** Set for [LayerKind.ADJUSTMENT] layers created with a spec (engine-api v3). */
    val adjustment: AdjustmentInfo? = null,
    /** Persistent editable path records for [LayerKind.SHAPE]; rendered pixels are derived cache only. */
    val vectorPaths: List<VectorPathDraft> = emptyList(),
    /** Editable content of [LayerKind.TEXT] layers; their pixels are a rendered cache. */
    val text: TextLayerInfo? = null,
    /** Layer ▸ Layer Style effects; [hasStyles] mirrors whether any is enabled. */
    val styles: LayerStyles? = null,
    /** Set for [LayerKind.SMART_OBJECT] layers: the embedded source and its transform. */
    val smartObject: SmartObjectInfo? = null,
    /** Set for [LayerKind.FILL] layers: what they paint across the canvas. */
    val fill: FillLayerInfo? = null,
    /** Pixels kept outside the canvas by a crop with "Delete Cropped Pixels" off. */
    val hiddenPixels: HiddenPixelsInfo? = null,
) {
    val isGroup: Boolean get() = kind == LayerKind.GROUP
}

/**
 * The document layer stack. [layers] is ordered top-most first (layers-panel order).
 * [active] is the set of targeted layers; [primary] receives painting.
 */
@Serializable
data class LayerTree(
    val layers: List<LayerNode> = emptyList(),
    val active: Set<LayerId> = emptySet(),
    val primary: LayerId? = null,
) {
    /** Depth-first, panel order, including group children. */
    fun flatten(): List<LayerNode> = buildList {
        fun walk(nodes: List<LayerNode>) {
            nodes.forEach {
                add(it)
                walk(it.children)
            }
        }
        walk(layers)
    }

    fun find(id: LayerId): LayerNode? = flatten().firstOrNull { it.id == id }

    fun parentOf(id: LayerId): LayerNode? = flatten().firstOrNull { p -> p.children.any { it.id == id } }

    fun depthOf(id: LayerId): Int {
        var depth = 0
        var cursor = parentOf(id)
        while (cursor != null) {
            depth++
            cursor = parentOf(cursor.id)
        }
        return depth
    }

    val size: Int get() = flatten().size

    companion object {
        val Empty = LayerTree()
    }
}
