package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.BackgroundSampling
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RetouchEffect
import app.croptop.core.engine.api.SpotHealType
import app.croptop.core.engine.api.StrokeSession
import app.croptop.core.engine.api.ToneRange
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState

/** The retouch tools this controller drives. */
val RetouchTools = listOf(
    ToolId.SPOT_HEALING, ToolId.HEALING, ToolId.CLONE_STAMP,
    ToolId.PATCH, ToolId.RED_EYE,
    ToolId.BLUR, ToolId.SHARPEN, ToolId.SMUDGE,
    ToolId.DODGE, ToolId.BURN, ToolId.SPONGE,
    ToolId.HISTORY_BRUSH, ToolId.BACKGROUND_ERASER,
)

/**
 * Brush-driven retouching: the engine changes the pixels under the stroke (clone, heal, dodge/burn,
 * sponge, blur/sharpen, smudge) instead of painting a color. Clone Stamp and Healing Brush sample
 * from a source set with Alt+click; with Aligned, the source keeps its offset across strokes.
 */
class RetouchTool(
    override val tool: ToolId,
    private val begin: (size: Float, hardness: Float, effect: RetouchEffect) -> StrokeSession?,
    private val onResult: (CommandResult) -> Unit,
    private val notify: (String) -> Unit,
    private val onCancel: () -> Unit = {},
    /** Labels of the document's history states, oldest first; the History Brush paints back from one of them. */
    private val historyLabels: () -> List<String> = { emptyList() },
    /** The colour swatches: the Background Eraser can remove the background swatch's colour and protect the foreground's. */
    private val foreground: () -> RgbaColor = { RgbaColor.Black },
    private val background: () -> RgbaColor = { RgbaColor.White },
) : ToolController {
    var onActionCompleted: (String) -> Unit = {}
    var onActionUnsupported: (String, String) -> Unit = { _, _ -> }

    init {
        require(tool in RetouchTools) { "$tool isn't a retouch tool" }
    }

    var size by mutableStateOf(if (tool == ToolId.SPOT_HEALING) 40f else 60f)
    var hardness by mutableStateOf(if (tool in setOf(ToolId.CLONE_STAMP, ToolId.HEALING)) 0.8f else 0f)
    var strength by mutableStateOf(0.5f)
    var sampleAll by mutableStateOf(false)
    var aligned by mutableStateOf(true)

    /** Shows the clone/heal source marker and the live sampled-location crosshair. */
    var showSourceOverlay by mutableStateOf(true)
    var range by mutableStateOf(ToneRange.MIDTONES)
    var protect by mutableStateOf(true)
    var saturate by mutableStateOf(false)
    var fingerPainting by mutableStateOf(false)
    var spotType by mutableStateOf(SpotHealType.CONTENT_AWARE)

    /** History Brush: the history state painted back from (0 = Open, the first state). */
    var historyIndex by mutableStateOf(0)

    /** Background Eraser: how far a colour may stray (0–1 is 0–100%), whether it stays connected, where the colour comes from. */
    var eraserTolerance by mutableStateOf(0.3f)
    var eraserContiguous by mutableStateOf(true)
    var eraserSampling by mutableStateOf(BackgroundSampling.CONTINUOUS)
    var protectForeground by mutableStateOf(false)

    /** Alt+click point for Clone/Heal; null until set. */
    var source by mutableStateOf<DocPoint?>(null)
        private set

    /** Source minus destination, fixed by the first stroke after the source is set (Aligned). */
    private var offset: Offset? = null
    private var session: StrokeSession? = null
    private var start = DocPoint(0f, 0f)
    private var current by mutableStateOf<DocPoint?>(null)
    private var startTime = 0L

    private val usesSource get() = tool == ToolId.CLONE_STAMP || tool == ToolId.HEALING

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                if (tool == ToolId.PATCH || tool == ToolId.RED_EYE) {
                    onActionUnsupported(
                        tool.displayName,
                        "${tool.displayName} needs a document retouch operation, which this engine build does not expose yet.",
                    )
                    return true
                }
                if (usesSource && event.modifiers.alt) {
                    source = event.doc
                    offset = null
                    return true
                }
                val effect = effect(event.doc) ?: run {
                    notify("Alt-click to set the ${tool.displayName} source first")
                    return true
                }
                start = event.doc
                startTime = System.nanoTime()
                session = begin(size, hardness, effect)
                session?.add(pack(event))
                current = event.doc
            }

            PointerPhase.MOVE -> session?.let {
                it.add(pack(event))
                current = event.doc
            }

            PointerPhase.UP -> {
                session?.let { onResult(it.end()) }
                session = null
                current = null
            }

            PointerPhase.CANCEL -> {
                val canceledSession = session
                canceledSession?.cancel()
                if (canceledSession != null) onCancel()
                session = null
                current = null
            }
        }
        return true
    }

    /** The effect for a stroke starting at [at]; null for Clone/Heal without a source. */
    fun effect(at: DocPoint): RetouchEffect? = when (tool) {
        ToolId.CLONE_STAMP, ToolId.HEALING -> {
            val src = strokeSource(at) ?: return null
            if (tool == ToolId.CLONE_STAMP) RetouchEffect.Clone(src, sampleAll) else RetouchEffect.Heal(src, sampleAll)
        }

        ToolId.SPOT_HEALING -> RetouchEffect.SpotHeal(spotType, sampleAll)

        ToolId.BACKGROUND_ERASER -> RetouchEffect.BackgroundErase(
            tolerance = eraserTolerance,
            contiguous = eraserContiguous,
            sampling = eraserSampling,
            swatch = if (eraserSampling == BackgroundSampling.SWATCH) background() else null,
            protect = if (protectForeground) foreground() else null,
        )

        ToolId.HISTORY_BRUSH -> RetouchEffect.HistoryPaint(historyIndex.coerceIn(0, (historyLabels().size - 1).coerceAtLeast(0)))

        ToolId.BLUR -> RetouchEffect.Focus(sharpen = false, strength, sampleAll)

        ToolId.SHARPEN -> RetouchEffect.Focus(sharpen = true, strength, sampleAll, protect)

        ToolId.SMUDGE -> RetouchEffect.Smudge(strength, fingerPainting, sampleAll)

        ToolId.DODGE -> RetouchEffect.Tone(burn = false, range, strength, protect)

        ToolId.BURN -> RetouchEffect.Tone(burn = true, range, strength, protect)

        ToolId.SPONGE -> RetouchEffect.Sponge(saturate, strength, protect)

        else -> null
    }

    /** Aligned: the first stroke fixes source − destination; otherwise every stroke starts at the source. */
    private fun strokeSource(at: DocPoint): DocPoint? {
        val s = source ?: return null
        if (!aligned) return s
        val o = offset ?: Offset(s.x - at.x, s.y - at.y).also { offset = it }
        return DocPoint(at.x + o.x, at.y + o.y)
    }

    private fun pack(e: ToolPointerEvent): FloatArray {
        val pressure = if (e.pressure <= 0f) 1f else e.pressure.coerceIn(0f, 1f)
        return floatArrayOf(e.doc.x, e.doc.y, pressure, 0f, 0f, 0f, (System.nanoTime() - startTime) / 1e9f)
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val src = source
        val painting = current
        val o = offset
        val diameter = size
        Canvas(Modifier.fillMaxSize()) {
            if (hover != null) {
                val r = (diameter / 2f * viewport.zoom).coerceAtLeast(1f)
                val at = Offset(hover.x, hover.y)
                drawCircle(Color.White, r, at, style = Stroke(1.5.dp.toPx()))
                drawCircle(Color.Black, r, at, style = Stroke(0.75.dp.toPx()))
            }
            if (!usesSource || src == null || !showSourceOverlay) return@Canvas
            // Where pixels are sampled from: follows the stroke, or the hover once the offset is fixed.
            val sampleAt = when {
                painting != null && o != null -> DocPoint(painting.x + o.x, painting.y + o.y)
                painting != null -> DocPoint(src.x + painting.x - start.x, src.y + painting.y - start.y)
                hover != null && o != null -> viewport.viewToDoc(hover).let { DocPoint(it.x + o.x, it.y + o.y) }
                else -> src
            }
            val v = viewport.docToView(sampleAt)
            crosshair(Offset(v.x, v.y))
        }
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeBarSlider(
                "Size",
                size,
                { size = it },
                range = 1f..5000f,
                defaultValue = 30f,
                curve = 3f,
                unit = "px",
                width = 176.dp,
                contentDescription = "Brush size",
                onValueChangeFinished = { onActionCompleted("Set ${tool.displayName.lowercase()} size") },
            )
            AeBarSlider(
                "Hardness",
                hardness * 100f,
                { hardness = it / 100f },
                range = 0f..100f,
                defaultValue = 50f,
                unit = "%",
                width = 176.dp,
                contentDescription = "Hardness",
                onValueChangeFinished = { onActionCompleted("Set ${tool.displayName.lowercase()} hardness") },
            )
            when (tool) {
                ToolId.CLONE_STAMP, ToolId.HEALING -> {
                    AeCheckbox(aligned, {
                        aligned = it
                        onActionCompleted(if (it) "Enable aligned sampling" else "Disable aligned sampling")
                    }, "Aligned")
                    AeCheckbox(sampleAll, {
                        sampleAll = it
                        onActionCompleted(
                            samplingAction(tool, it),
                        )
                    }, "Sample all layers")
                    AeCheckbox(showSourceOverlay, {
                        showSourceOverlay = it
                        onActionCompleted(if (it) "Show source overlay" else "Hide source overlay")
                    }, "Show source overlay")
                    Hint(if (source == null) "Alt-click to set the source" else "Alt-click to set a new source")
                }

                ToolId.SPOT_HEALING -> {
                    Field("Type") {
                        AeDropdown(SpotHealType.entries, spotType, {
                            spotType = it
                            onActionCompleted("Set spot healing type to ${it.label.lowercase()}")
                        }, { it.label })
                    }
                    AeCheckbox(sampleAll, {
                        sampleAll = it
                        onActionCompleted(if (it) "Sample all layers for spot healing" else "Sample active layer for spot healing")
                    }, "Sample all layers")
                    Hint("Paint over a blemish to remove it")
                }

                ToolId.BACKGROUND_ERASER -> {
                    Field("Tolerance", labelShown = false) {
                        Percent(eraserTolerance, "Tolerance", { eraserTolerance = it }) {
                            onActionCompleted("Set background eraser tolerance")
                        }
                    }
                    Field("Sampling") {
                        AeDropdown(BackgroundSampling.entries, eraserSampling, {
                            eraserSampling = it
                            onActionCompleted("Set background eraser sampling to ${it.label.lowercase()}")
                        }, { it.label })
                    }
                    AeCheckbox(eraserContiguous, {
                        eraserContiguous = it
                        onActionCompleted(if (it) "Erase contiguous colours only" else "Erase the colour everywhere under the brush")
                    }, "Contiguous")
                    AeCheckbox(protectForeground, {
                        protectForeground = it
                        onActionCompleted(if (it) "Protect the foreground colour" else "Stop protecting the foreground colour")
                    }, "Protect foreground")
                    Hint("Drag so the brush centre stays on the colour to remove")
                }

                ToolId.HISTORY_BRUSH -> {
                    val labels = historyLabels()
                    val chosen = historyIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
                    Field("Source") {
                        AeDropdown(labels.indices.toList(), chosen, {
                            historyIndex = it
                            onActionCompleted("Set history brush source to ${labels.getOrNull(it).orEmpty()}")
                        }, { "${it + 1}. ${labels.getOrNull(it).orEmpty()}" })
                    }
                    Hint("Paint to restore the layer to the chosen history state")
                }

                ToolId.PATCH, ToolId.RED_EYE -> Hint(
                    "${tool.displayName} is waiting for document retouch support",
                )

                ToolId.BLUR, ToolId.SHARPEN, ToolId.SMUDGE -> {
                    Field("Strength", labelShown = false) {
                        Percent(strength, "Strength", { strength = it }) {
                            onActionCompleted("Set ${tool.displayName.lowercase()} strength")
                        }
                    }
                    AeCheckbox(sampleAll, {
                        sampleAll = it
                        onActionCompleted(
                            samplingAction(tool, it),
                        )
                    }, "Sample all layers")
                    if (tool == ToolId.SHARPEN) {
                        AeCheckbox(protect, {
                            protect = it
                            onActionCompleted(if (it) "Protect sharpened detail" else "Allow sharpened detail changes")
                        }, "Protect detail")
                    }
                    if (tool == ToolId.SMUDGE) {
                        AeCheckbox(fingerPainting, {
                            fingerPainting = it
                            onActionCompleted(if (it) "Enable finger painting" else "Disable finger painting")
                        }, "Finger painting")
                    }
                }

                ToolId.DODGE, ToolId.BURN -> {
                    Field("Range") {
                        AeDropdown(ToneRange.entries, range, {
                            range = it
                            onActionCompleted("Set ${tool.displayName.lowercase()} range to ${it.label.lowercase()}")
                        }, { it.label })
                    }
                    Field("Exposure", labelShown = false) {
                        Percent(strength, "Exposure", { strength = it }) {
                            onActionCompleted("Set ${tool.displayName.lowercase()} exposure")
                        }
                    }
                    AeCheckbox(protect, {
                        protect = it
                        onActionCompleted(
                            if (it) "Protect ${tool.displayName.lowercase()} tones" else "Allow ${tool.displayName.lowercase()} tone clipping",
                        )
                    }, "Protect tones")
                }

                ToolId.SPONGE -> {
                    AeSegmented(listOf(false, true), saturate, {
                        saturate = it
                        onActionCompleted(if (it) "Set sponge mode to saturate" else "Set sponge mode to desaturate")
                    }, { if (it) "Saturate" else "Desaturate" })
                    Field("Flow", labelShown = false) {
                        Percent(strength, "Flow", { strength = it }) {
                            onActionCompleted("Set sponge flow")
                        }
                    }
                    AeCheckbox(protect, {
                        protect = it
                        onActionCompleted(if (it) "Protect color vibrance" else "Allow color vibrance changes")
                    }, "Vibrance")
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun Field(label: String, labelShown: Boolean = true, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (labelShown) AeText(label, style = Ae.type.caption, color = Ae.colors.textSecondary)
        content()
    }
}

@Composable
private fun Percent(value: Float, description: String, change: (Float) -> Unit, onCommit: () -> Unit) =
    AeBarSlider(
        description,
        value * 100f,
        { change(it / 100f) },
        range = 0f..100f,
        defaultValue = 50f,
        unit = "%",
        width = 168.dp,
        contentDescription = description,
        onValueChangeFinished = onCommit,
    )

private fun samplingAction(tool: ToolId, allLayers: Boolean) =
    if (allLayers) {
        "Sample all layers for ${tool.displayName.lowercase()}"
    } else {
        "Sample active layer for ${tool.displayName.lowercase()}"
    }

@Composable
private fun Hint(text: String) = AeText(text, style = Ae.type.caption, color = Ae.colors.textTertiary)

/** Clone source marker: a small crosshair, black on white so it reads on any image. */
private fun DrawScope.crosshair(at: Offset) {
    val arm = 7.dp.toPx()
    for ((color, width) in listOf(Color.White to 3.dp.toPx(), Color.Black to 1.25.dp.toPx())) {
        drawLine(color, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), width)
        drawLine(color, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), width)
    }
}
