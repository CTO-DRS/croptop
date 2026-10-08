package app.croptop.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.ColorRangeSession
import app.croptop.core.engine.api.ColorRangeSpec
import app.croptop.core.engine.api.ColorRangeTarget
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ToolId
import kotlin.math.abs

/**
 * Select ▸ Color Range as a non-modal mode: the options bar holds the settings and clicking the canvas
 * samples colors (Shift adds, Alt removes), so the live selection preview stays visible and editable.
 * Enter or OK commits one history step; Esc or Cancel restores the previous selection.
 */
class ColorRangeMode(
    private val session: ColorRangeSession,
    private val sample: (DocPoint) -> RgbaColor?,
    private val onCommit: (ColorRangeSession) -> Boolean,
    private val onEnd: () -> Unit,
) : ToolController {
    override val tool = ToolId.EYEDROPPER
    var onActionCompleted: (String) -> Unit = {}
    var target by mutableStateOf(ColorRangeTarget.SAMPLED)
        private set
    var samples by mutableStateOf<List<RgbaColor>>(emptyList())
        private set
    var fuzziness by mutableStateOf(40)
        private set
    var invert by mutableStateOf(false)
        private set

    /** Nothing previews until the first sample or setting change, so opening doesn't clear the selection. */
    private var touched = false

    val spec: ColorRangeSpec get() = ColorRangeSpec(target, samples, fuzziness, invert)

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        if (event.phase != PointerPhase.DOWN) return true
        val p = event.doc
        if (!(p.x >= 0f && p.y >= 0f && p.x < ctx.document.width && p.y < ctx.document.height)) return true
        val color = sample(p) ?: return true
        samples = when {
            event.modifiers.alt -> samples.filterNot { it.near(color) }
            event.modifiers.shift -> if (samples.any { it.near(color) }) samples else samples + color
            else -> listOf(color)
        }
        target = ColorRangeTarget.SAMPLED
        push()
        onActionCompleted("Sample color range")
        return true
    }

    fun chooseTarget(value: ColorRangeTarget) {
        target = value
        push()
        onActionCompleted("Set color range target to ${value.label.lowercase()}")
    }

    fun chooseFuzziness(value: Int) {
        fuzziness = value.coerceIn(0, ColorRangeSpec.MAX_FUZZINESS)
        push()
        onActionCompleted("Set color range fuzziness")
    }

    fun chooseInvert(value: Boolean) {
        invert = value
        push()
        onActionCompleted(if (value) "Invert color range" else "Use normal color range")
    }

    private fun push() {
        touched = true
        session.update(spec)
    }

    fun commit(): Boolean {
        if (!touched) {
            cancel()
            return true
        }
        val ok = onCommit(session)
        if (ok) onEnd()
        return ok
    }

    fun cancel() {
        session.cancel()
        onEnd()
    }

    /** Enter commits, Esc cancels. */
    fun onKey(enter: Boolean): Boolean {
        if (enter) commit() else cancel()
        return true
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeText("Color Range", style = Ae.type.label)
            AeText("Select", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeDropdown(ColorRangeTarget.entries, target, ::chooseTarget, { it.label })
            AeBarSlider(
                "Fuzziness",
                fuzziness.toFloat(),
                { chooseFuzziness(it.toInt()) },
                range = 0f..ColorRangeSpec.MAX_FUZZINESS.toFloat(),
                defaultValue = 40f,
                width = 170.dp,
                contentDescription = "Fuzziness",
            )
            if (target == ColorRangeTarget.SAMPLED) {
                if (samples.isEmpty()) {
                    AeText("Click the image to sample · Shift adds · Alt removes", style = Ae.type.caption, color = Ae.colors.textTertiary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        samples.take(MAX_CHIPS).forEach { c ->
                            Box(
                                Modifier.size(
                                    16.dp,
                                ).background(Color(c.r, c.g, c.b, c.a), Ae.shapes.chip).border(1.dp, Ae.colors.lineStrong, Ae.shapes.chip),
                            )
                        }
                        if (samples.size > MAX_CHIPS) AeText("+${samples.size - MAX_CHIPS}", style = Ae.type.caption, color = Ae.colors.textSecondary)
                    }
                }
            }
            AeCheckbox(invert, ::chooseInvert, "Invert")
            AeButton("Cancel", ::cancel, style = AeButtonStyle.Ghost)
            AeButton("OK", { commit() }, style = AeButtonStyle.Primary)
        }
    }

    private companion object {
        const val MAX_CHIPS = 8
    }
}

/** Two samples this close are the same color for add/remove (about 1% per channel). */
private fun RgbaColor.near(o: RgbaColor) = abs(r - o.r) < 0.01f && abs(g - o.g) < 0.01f && abs(b - o.b) < 0.01f
