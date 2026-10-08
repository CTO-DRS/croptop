package app.croptop.core.model

import kotlinx.serialization.Serializable

/** Dockable editor panels. */
@Serializable
enum class DockPanel(val title: String) {
    HISTOGRAM("Histogram"),
    NAVIGATOR("Navigator"),
    COLOR("Color"),
    PROPERTIES("Properties"),
    HISTORY("History"),
    LAYERS("Layers"),
    CHANNELS("Channels"),
    PATHS("Paths"),
    CHARACTER("Character"),
    PARAGRAPH("Paragraph"),
    BRUSHES("Brushes"),
    BRUSH_SETTINGS("Brush Settings"),
}

@Serializable
data class DockPanelState(
    val panel: DockPanel,
    val visible: Boolean = true,
    val expanded: Boolean = true,
    /** Floating panels render over the canvas at [floatX]/[floatY] (dp from the canvas top-left). */
    val floating: Boolean = false,
    val floatX: Float = 24f,
    val floatY: Float = 24f,
)

/** A named arrangement of the editor chrome. Order of [panels] = dock order, top first. */
@Serializable
data class WorkspaceLayout(
    val name: String,
    val panels: List<DockPanelState>,
    val dockWidthDp: Float = 280f,
) {
    fun state(p: DockPanel): DockPanelState = panels.firstOrNull { it.panel == p } ?: DockPanelState(p, visible = false)

    /** Layouts saved before [p] existed get it appended (at the bottom of the dock). */
    fun update(p: DockPanel, transform: (DockPanelState) -> DockPanelState): WorkspaceLayout =
        if (panels.none { it.panel == p }) {
            copy(panels = panels + transform(state(p)))
        } else {
            copy(panels = panels.map { if (it.panel == p) transform(it) else it })
        }

    /** Moves [p] one slot up (-1) or down (+1) in the dock. */
    fun move(p: DockPanel, delta: Int): WorkspaceLayout {
        val i = panels.indexOfFirst { it.panel == p }
        val j = (i + delta).coerceIn(0, panels.lastIndex)
        if (i < 0 || i == j) return this
        return copy(panels = panels.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun withDockWidth(dp: Float): WorkspaceLayout = copy(dockWidthDp = dp.coerceIn(MIN_DOCK_DP, MAX_DOCK_DP))

    companion object {
        const val MIN_DOCK_DP = 240f
        const val MAX_DOCK_DP = 480f

        private fun of(name: String, vararg p: DockPanelState) = WorkspaceLayout(name, p.toList())

        val Essentials = of(
            "Essentials",
            DockPanelState(DockPanel.HISTOGRAM, expanded = false),
            DockPanelState(DockPanel.NAVIGATOR, expanded = false),
            DockPanelState(DockPanel.COLOR, expanded = false),
            DockPanelState(DockPanel.PROPERTIES),
            DockPanelState(DockPanel.HISTORY, expanded = false),
            DockPanelState(DockPanel.LAYERS),
            DockPanelState(DockPanel.CHANNELS, visible = false),
            DockPanelState(DockPanel.PATHS, visible = false),
            DockPanelState(DockPanel.CHARACTER, visible = false),
            DockPanelState(DockPanel.PARAGRAPH, visible = false),
            DockPanelState(DockPanel.BRUSHES, visible = false),
            DockPanelState(DockPanel.BRUSH_SETTINGS, visible = false),
        )
        val Photography = of(
            "Photography",
            DockPanelState(DockPanel.HISTOGRAM),
            DockPanelState(DockPanel.NAVIGATOR),
            DockPanelState(DockPanel.COLOR, visible = false),
            DockPanelState(DockPanel.PROPERTIES),
            DockPanelState(DockPanel.HISTORY, expanded = false),
            DockPanelState(DockPanel.LAYERS),
            DockPanelState(DockPanel.CHANNELS, visible = false),
            DockPanelState(DockPanel.PATHS, visible = false),
            DockPanelState(DockPanel.CHARACTER, visible = false),
            DockPanelState(DockPanel.PARAGRAPH, visible = false),
            DockPanelState(DockPanel.BRUSHES, visible = false),
            DockPanelState(DockPanel.BRUSH_SETTINGS, visible = false),
        )
        val Retouch = of(
            "Retouch",
            DockPanelState(DockPanel.HISTOGRAM, expanded = false),
            DockPanelState(DockPanel.NAVIGATOR, expanded = false),
            DockPanelState(DockPanel.COLOR, expanded = false),
            DockPanelState(DockPanel.HISTORY),
            DockPanelState(DockPanel.PROPERTIES, expanded = false),
            DockPanelState(DockPanel.LAYERS),
            DockPanelState(DockPanel.CHANNELS, visible = false),
            DockPanelState(DockPanel.PATHS, visible = false),
            DockPanelState(DockPanel.CHARACTER, visible = false),
            DockPanelState(DockPanel.PARAGRAPH, visible = false),
            DockPanelState(DockPanel.BRUSHES, visible = false),
            DockPanelState(DockPanel.BRUSH_SETTINGS, visible = false),
        )
        val Painting = of(
            "Painting",
            DockPanelState(DockPanel.HISTOGRAM, visible = false),
            DockPanelState(DockPanel.NAVIGATOR, expanded = false),
            DockPanelState(DockPanel.COLOR),
            DockPanelState(DockPanel.BRUSHES, expanded = false),
            DockPanelState(DockPanel.BRUSH_SETTINGS, expanded = false),
            DockPanelState(DockPanel.PROPERTIES, visible = false),
            DockPanelState(DockPanel.HISTORY, expanded = false),
            DockPanelState(DockPanel.LAYERS),
            DockPanelState(DockPanel.CHANNELS, visible = false),
            DockPanelState(DockPanel.PATHS, visible = false),
            DockPanelState(DockPanel.CHARACTER, visible = false),
            DockPanelState(DockPanel.PARAGRAPH, visible = false),
        )
        val Presets = listOf(Essentials, Photography, Retouch, Painting)
    }
}
