package app.croptop.core.engine.api

import app.croptop.core.model.DocPoint

/** Where the Background Eraser takes the colour to remove from. */
enum class BackgroundSampling(val label: String) {
    CONTINUOUS("Continuous"),
    ONCE("Once"),
    SWATCH("Background swatch"),
}

/** Tonal range a Dodge/Burn stroke affects. */
enum class ToneRange(val label: String) { SHADOWS("Shadows"), MIDTONES("Midtones"), HIGHLIGHTS("Highlights") }

/** How Spot Healing finds replacement pixels. */
enum class SpotHealType(val label: String) { CONTENT_AWARE("Content-Aware"), PROXIMITY_MATCH("Proximity Match"), CREATE_TEXTURE("Create Texture") }

/**
 * What a retouch stroke does to the pixels under the brush instead of painting a color. Strengths are
 * 0–1. For [Clone] and [Heal], [source] is the point sampled under the stroke's first sample; later
 * samples keep the same offset from it.
 */
sealed interface RetouchEffect {
    /** History label, e.g. "Clone Stamp". */
    val label: String

    data class Clone(val source: DocPoint, val sampleAll: Boolean = false) : RetouchEffect {
        override val label get() = "Clone Stamp"

        init {
            requireFinite(source)
        }
    }

    data class Heal(val source: DocPoint, val sampleAll: Boolean = false) : RetouchEffect {
        override val label get() = "Healing Brush"

        init {
            requireFinite(source)
        }
    }

    data class SpotHeal(val type: SpotHealType = SpotHealType.CONTENT_AWARE, val sampleAll: Boolean = false) : RetouchEffect {
        override val label get() = "Spot Healing Brush"
    }

    data class Tone(val burn: Boolean, val range: ToneRange = ToneRange.MIDTONES, val exposure: Float = 0.5f, val protectTones: Boolean = true) :
        RetouchEffect {
        override val label get() = if (burn) "Burn" else "Dodge"

        init {
            requireStrength(exposure, "Exposure")
        }
    }

    data class Sponge(val saturate: Boolean, val flow: Float = 0.5f, val vibrance: Boolean = true) : RetouchEffect {
        override val label get() = "Sponge"

        init {
            requireStrength(flow, "Flow")
        }
    }

    data class Focus(val sharpen: Boolean, val strength: Float = 0.5f, val sampleAll: Boolean = false, val protectDetail: Boolean = true) :
        RetouchEffect {
        override val label get() = if (sharpen) "Sharpen" else "Blur"

        init {
            requireStrength(strength, "Strength")
        }
    }

    /**
     * History Brush: paints the target layer back to how it looked in history state [stateIndex] (0 = the first state, "Open"),
     * at the same positions. The state must still hold the layer at the same canvas size.
     */
    data class HistoryPaint(val stateIndex: Int) : RetouchEffect {
        override val label get() = "History Brush"

        init {
            require(stateIndex >= 0) { "History state must be 0 or more" }
        }
    }

    /**
     * Background Eraser: erases to transparency the pixels whose colour is near the one sampled at the brush centre. [tolerance]
     * (0–1) is how far a colour may stray; [contiguous] limits it to pixels connected to the centre; [protect] is never erased.
     */
    data class BackgroundErase(
        val tolerance: Float = 0.3f,
        val contiguous: Boolean = true,
        val sampling: BackgroundSampling = BackgroundSampling.CONTINUOUS,
        /** The colour removed for [BackgroundSampling.SWATCH]. */
        val swatch: app.croptop.core.model.RgbaColor? = null,
        val protect: app.croptop.core.model.RgbaColor? = null,
    ) : RetouchEffect {
        override val label get() = "Background Eraser"

        init {
            requireStrength(tolerance, "Tolerance")
            require(sampling != BackgroundSampling.SWATCH || swatch != null) { "Swatch sampling needs a colour" }
        }
    }

    data class Smudge(val strength: Float = 0.5f, val fingerPainting: Boolean = false, val sampleAll: Boolean = false) : RetouchEffect {
        override val label get() = "Smudge"

        init {
            requireStrength(strength, "Strength")
        }
    }
}

private fun requireFinite(p: DocPoint) = require(p.x.isFinite() && p.y.isFinite()) { "Source point must be finite" }

private fun requireStrength(v: Float, name: String) = require(v.isFinite() && v in 0f..1f) { "$name must be 0–100%" }

/** Default session for engines that don't support a stroke kind yet: [end] reports why. */
class UnsupportedStrokeSession(private val what: String) : StrokeSession {
    override fun add(samples: FloatArray) = Unit

    override fun end(): CommandResult = unsupported(what)

    override fun cancel() = Unit
}
