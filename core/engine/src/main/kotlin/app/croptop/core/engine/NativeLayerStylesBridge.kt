package app.croptop.core.engine

/**
 * Layer styles (native `layer_styles`): renders each effect as its own canvas-sized RGBA store with a blend mode and opacity, in the
 * order the compositor must stack them, or flattens the layer with its styles onto a backdrop. Settings are encoded in the layout of
 * `layer_styles_codec.h`. The caller owns every returned handle (`NativeSelectionBridge.destroyStore`).
 */
internal object NativeLayerStylesBridge {
    /** Blend mode ordinals follow the native `BlendMode` enum. */
    data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
        fun toList() = listOf(r, g, b, a)
    }

    data class Shadow(
        val enabled: Boolean = false,
        val color: Rgba = Rgba(0f, 0f, 0f),
        val mode: Int = MULTIPLY,
        val opacity: Float = 0.75f,
        val angle: Float = 120f,
        val distance: Float = 5f,
        val spread: Float = 0f,
        val size: Float = 5f,
    )

    data class Glow(
        val enabled: Boolean = false,
        val color: Rgba = Rgba(1f, 1f, 190 / 255f),
        val mode: Int = SCREEN,
        val opacity: Float = 0.75f,
        val spread: Float = 0f,
        val size: Float = 5f,
        val fromCenter: Boolean = false,
    )

    data class Bevel(
        val enabled: Boolean = false,
        val style: Int = 0,
        val chiselHard: Boolean = false,
        val depth: Float = 1f,
        val up: Boolean = true,
        val size: Float = 5f,
        val soften: Float = 0f,
        val angle: Float = 120f,
        val altitude: Float = 30f,
        val highlight: Rgba = Rgba(1f, 1f, 1f),
        val highlightMode: Int = SCREEN,
        val highlightOpacity: Float = 0.75f,
        val shadow: Rgba = Rgba(0f, 0f, 0f),
        val shadowMode: Int = MULTIPLY,
        val shadowOpacity: Float = 0.75f,
    )

    data class Satin(
        val enabled: Boolean = false,
        val color: Rgba = Rgba(0f, 0f, 0f),
        val mode: Int = MULTIPLY,
        val opacity: Float = 0.5f,
        val angle: Float = 19f,
        val distance: Float = 11f,
        val size: Float = 14f,
        val invert: Boolean = true,
    )

    data class ColorOverlay(val enabled: Boolean = false, val color: Rgba = Rgba(1f, 0f, 0f), val mode: Int = NORMAL, val opacity: Float = 1f)

    data class GradientStop(val position: Float, val color: Rgba)

    data class GradientOverlay(
        val enabled: Boolean = false,
        val stops: List<GradientStop> = listOf(GradientStop(0f, Rgba(0f, 0f, 0f)), GradientStop(1f, Rgba(1f, 1f, 1f))),
        val mode: Int = NORMAL,
        val opacity: Float = 1f,
        val angle: Float = 90f,
        val scale: Float = 1f,
        val reverse: Boolean = false,
    )

    data class PatternOverlay(val enabled: Boolean = false, val mode: Int = NORMAL, val opacity: Float = 1f, val scale: Float = 1f)

    /** [position]: 0 outside, 1 inside, 2 center. */
    data class Stroke(
        val enabled: Boolean = false,
        val color: Rgba = Rgba(1f, 0f, 0f),
        val mode: Int = NORMAL,
        val opacity: Float = 1f,
        val size: Float = 3f,
        val position: Int = 0,
    )

    data class Styles(
        val dropShadow: Shadow = Shadow(),
        val outerGlow: Glow = Glow(),
        val bevel: Bevel = Bevel(),
        val innerShadow: Shadow = Shadow(),
        val innerGlow: Glow = Glow(),
        val satin: Satin = Satin(),
        val colorOverlay: ColorOverlay = ColorOverlay(),
        val gradientOverlay: GradientOverlay = GradientOverlay(),
        val patternOverlay: PatternOverlay = PatternOverlay(),
        val stroke: Stroke = Stroke(),
    )

    class Effect(val storeHandle: Long, val blendMode: Int, val opacity: Float, val belowContent: Boolean)

    /** Effect layers in compositing order. [patternHandle] is required when the pattern overlay is enabled. */
    fun render(layerHandle: Long, styles: Styles, patternHandle: Long = 0L): List<Effect> {
        val raw = checkNotNull(nativeRender(layerHandle, encode(styles), patternHandle)) { nativeLastError().ifBlank { "Layer styles failed" } }
        return (raw.indices step 4).map { i -> Effect(raw[i], raw[i + 1].toInt(), Float.fromBits(raw[i + 2].toInt()), raw[i + 3] == 1L) }
    }

    fun flatten(layerHandle: Long, styles: Styles, backdropHandle: Long, fillOpacity: Float = 1f, patternHandle: Long = 0L): Long {
        val out = nativeFlatten(layerHandle, encode(styles), patternHandle, backdropHandle, fillOpacity)
        check(out != 0L) { nativeLastError().ifBlank { "Layer styles failed" } }
        return out
    }

    fun encode(s: Styles): FloatArray {
        val out = ArrayList<Float>(180)
        fun flag(b: Boolean) = if (b) 1f else 0f
        fun shadow(x: Shadow) {
            out += flag(x.enabled)
            out += x.color.toList()
            out +=
                listOf(x.mode.toFloat(), x.opacity, x.angle, x.distance, x.spread, x.size)
        }
        fun glow(x: Glow) {
            out += flag(x.enabled)
            out += x.color.toList()
            out +=
                listOf(x.mode.toFloat(), x.opacity, x.spread, x.size, flag(x.fromCenter))
        }
        shadow(s.dropShadow)
        glow(s.outerGlow)
        val b = s.bevel
        out += flag(b.enabled)
        out += listOf(b.style.toFloat(), if (b.chiselHard) 1f else 0f, b.depth, flag(b.up), b.size, b.soften, b.angle, b.altitude)
        out += b.highlight.toList()
        out += b.highlightMode.toFloat()
        out += b.highlightOpacity
        out += b.shadow.toList()
        out += b.shadowMode.toFloat()
        out += b.shadowOpacity
        shadow(s.innerShadow)
        glow(s.innerGlow)
        val t = s.satin
        out += flag(t.enabled)
        out += t.color.toList()
        out += listOf(t.mode.toFloat(), t.opacity, t.angle, t.distance, t.size, flag(t.invert))
        val c = s.colorOverlay
        out += flag(c.enabled)
        out += c.color.toList()
        out += listOf(c.mode.toFloat(), c.opacity)
        val g = s.gradientOverlay
        require(g.stops.size in 1..MAX_GRADIENT_STOPS) { "a gradient overlay needs 1..$MAX_GRADIENT_STOPS stops" }
        out += flag(g.enabled)
        out += listOf(g.mode.toFloat(), g.opacity, g.angle, g.scale, flag(g.reverse), g.stops.size.toFloat())
        for (stop in g.stops) {
            out += stop.position
            out += stop.color.toList()
        }
        val p = s.patternOverlay
        out += flag(p.enabled)
        out += listOf(p.mode.toFloat(), p.opacity, p.scale)
        val k = s.stroke
        out += flag(k.enabled)
        out += k.color.toList()
        out += listOf(k.mode.toFloat(), k.opacity, k.size, k.position.toFloat())
        return out.toFloatArray()
    }

    /** Pattern library lookup (set by the engine): pattern id → RGBA store handle, or 0. */
    @Volatile
    var patternResolver: (String) -> Long = { 0L }

    private fun patternOf(styles: app.croptop.core.model.LayerStyles): Long =
        styles.patternOverlay.takeIf { it.enabled }?.patternId?.let(patternResolver) ?: 0L

    /** Attaches [styles] to a layer store so every composite draws them (null or nothing enabled detaches); returns an error or null. */
    fun attach(store: Long, styles: app.croptop.core.model.LayerStyles?): String? {
        val active = styles?.takeIf { it.anyEnabled } ?: return nativeAttach(store, null, 0L)
        val pattern = patternOf(active)
        return nativeAttach(store, encode(fromModel(active, pattern != 0L)), pattern)
    }

    /** [flatten] for model styles, resolving the pattern overlay's pattern. */
    fun flattenModel(layerHandle: Long, styles: app.croptop.core.model.LayerStyles, backdropHandle: Long, fillOpacity: Float): Long {
        val pattern = patternOf(styles)
        return flatten(layerHandle, fromModel(styles, pattern != 0L), backdropHandle, fillOpacity, pattern)
    }

    /** Inverse of [encode] straight into the model (PSD import); null when [v] is malformed or nothing is enabled. */
    fun toModel(v: FloatArray): app.croptop.core.model.LayerStyles? = runCatching {
        var i = 0
        fun f(): Float = v[i++]
        fun flag() = f() != 0f
        fun rgba() = app.croptop.core.model.RgbaColor(f(), f(), f(), f())
        fun mode() = app.croptop.core.model.BlendMode.entries.getOrElse(f().toInt() + 1) { app.croptop.core.model.BlendMode.NORMAL }
        fun shadow() = app.croptop.core.model.StyleShadow(flag(), rgba(), mode(), f(), f(), f(), f(), f())
        fun glow() = app.croptop.core.model.StyleGlow(flag(), rgba(), mode(), f(), f(), f(), flag())
        val dropShadow = shadow()
        val outerGlow = glow()
        val bevel = app.croptop.core.model.StyleBevel(
            enabled = flag(),
            style = app.croptop.core.model.BevelStyle.entries.getOrElse(f().toInt()) { app.croptop.core.model.BevelStyle.INNER_BEVEL },
            chiselHard = flag(), depth = f(), up = flag(), size = f(), soften = f(), angle = f(), altitude = f(),
            highlightColor = rgba(), highlightMode = mode(), highlightOpacity = f(),
            shadowColor = rgba(), shadowMode = mode(), shadowOpacity = f(),
        )
        val innerShadow = shadow()
        val innerGlow = glow()
        val satin = app.croptop.core.model.StyleSatin(flag(), rgba(), mode(), f(), f(), f(), f(), flag())
        val colorOverlay = app.croptop.core.model.StyleColorOverlay(flag(), rgba(), mode(), f())
        val gradientEnabled = flag()
        val gradientMode = mode()
        val gradientOpacity = f()
        val gradientAngle = f()
        val gradientScale = f()
        val gradientReverse = flag()
        val stops = List(f().toInt().coerceIn(0, MAX_GRADIENT_STOPS)) { app.croptop.core.model.StyleGradientStop(f(), rgba()) }
        val gradient = app.croptop.core.model.StyleGradientOverlay(
            gradientEnabled,
            stops.ifEmpty { app.croptop.core.model.StyleGradientOverlay().stops },
            gradientMode,
            gradientOpacity,
            gradientAngle,
            gradientScale,
            gradientReverse,
        )
        i += 4 // pattern overlay: not modelled (needs a pattern store)
        val stroke = app.croptop.core.model.StyleStroke(
            flag(),
            rgba(),
            mode(),
            f(),
            f(),
            app.croptop.core.model.StrokePosition.entries.getOrElse(f().toInt()) { app.croptop.core.model.StrokePosition.OUTSIDE },
        )
        app.croptop.core.model.LayerStyles(dropShadow, innerShadow, outerGlow, innerGlow, bevel, satin, colorOverlay, gradient, stroke)
    }.getOrNull()?.takeIf { it.anyEnabled }

    fun fromModel(s: app.croptop.core.model.LayerStyles, patternAvailable: Boolean = false): Styles {
        fun mode(m: app.croptop.core.model.BlendMode) = (m.ordinal - 1).coerceIn(0, 26)
        fun rgba(c: app.croptop.core.model.RgbaColor) = Rgba(c.r, c.g, c.b, c.a)
        fun shadow(x: app.croptop.core.model.StyleShadow) =
            Shadow(x.enabled, rgba(x.color), mode(x.blendMode), x.opacity, x.angle, x.distance, x.spread, x.size)
        fun glow(x: app.croptop.core.model.StyleGlow) =
            Glow(x.enabled, rgba(x.color), mode(x.blendMode), x.opacity, x.spread, x.size, x.fromCenter)
        val b = s.bevel
        val g = s.gradientOverlay
        require(g.stops.size in 1..MAX_GRADIENT_STOPS) { "a gradient overlay needs 1..$MAX_GRADIENT_STOPS stops" }
        return Styles(
            dropShadow = shadow(s.dropShadow),
            outerGlow = glow(s.outerGlow),
            bevel = Bevel(
                b.enabled, b.style.ordinal, b.chiselHard, b.depth, b.up, b.size, b.soften, b.angle, b.altitude,
                rgba(b.highlightColor), mode(b.highlightMode), b.highlightOpacity, rgba(b.shadowColor), mode(b.shadowMode), b.shadowOpacity,
            ),
            innerShadow = shadow(s.innerShadow),
            innerGlow = glow(s.innerGlow),
            satin = s.satin.let { Satin(it.enabled, rgba(it.color), mode(it.blendMode), it.opacity, it.angle, it.distance, it.size, it.invert) },
            colorOverlay = s.colorOverlay.let { ColorOverlay(it.enabled, rgba(it.color), mode(it.blendMode), it.opacity) },
            gradientOverlay = GradientOverlay(
                g.enabled,
                g.stops.map { GradientStop(it.position, rgba(it.color)) },
                mode(g.blendMode),
                g.opacity,
                g.angle,
                g.scale,
                g.reverse,
            ),
            stroke = s.stroke.let { Stroke(it.enabled, rgba(it.color), mode(it.blendMode), it.opacity, it.size, it.position.ordinal) },
            patternOverlay = s.patternOverlay.let {
                PatternOverlay(it.enabled && patternAvailable, mode(it.blendMode), it.opacity, it.scale.coerceIn(0.01f, 10f))
            },
        )
    }

    const val NORMAL = 0
    const val MULTIPLY = 3
    const val SCREEN = 8
    const val MAX_GRADIENT_STOPS = 16

    private external fun nativeLastError(): String
    private external fun nativeAttach(store: Long, settings: FloatArray?, patternHandle: Long): String?
    external fun nativeHasStyles(store: Long): Boolean
    private external fun nativeEncodePsdEffects(settings: FloatArray): ByteArray?

    /** Photoshop `lfx2` payload for [styles], or null when it has a pattern overlay (not written) or encoding fails. */
    fun psdEffects(styles: app.croptop.core.model.LayerStyles): ByteArray? =
        if (styles.patternOverlay.enabled) null else nativeEncodePsdEffects(encode(fromModel(styles)))
    private external fun nativeRender(layerHandle: Long, settings: FloatArray, patternHandle: Long): LongArray?
    private external fun nativeFlatten(layerHandle: Long, settings: FloatArray, patternHandle: Long, backdropHandle: Long, fillOpacity: Float): Long
}
