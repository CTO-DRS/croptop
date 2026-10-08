package app.croptop.feature.editor.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeMenu
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.component.AeToggleIconButton
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.DockPanel
import app.croptop.core.model.DockPanelState
import app.croptop.core.model.WorkspaceLayout
import kotlin.math.roundToInt

val DockPanel.icon: ImageVector
    get() = when (this) {
        DockPanel.HISTOGRAM -> AeIcons.Histogram
        DockPanel.NAVIGATOR -> AeIcons.Navigator
        DockPanel.COLOR -> AeIcons.Color
        DockPanel.PROPERTIES -> AeIcons.Properties
        DockPanel.HISTORY -> AeIcons.History
        DockPanel.LAYERS -> AeIcons.Layers
        DockPanel.CHANNELS -> AeIcons.Channels
        DockPanel.PATHS -> AeIcons.PathSelect
        DockPanel.CHARACTER -> AeIcons.Text
        DockPanel.PARAGRAPH -> AeIcons.Paragraph
        DockPanel.BRUSHES -> AeIcons.Brush
        DockPanel.BRUSH_SETTINGS -> AeIcons.Settings
    }

/** Content for one panel; supplied by the editor. */
typealias PanelContent = @Composable ColumnScope.(DockPanel) -> Unit

/** Header "…" menu for a panel: reorder, float/dock, close. */
@Composable
private fun PanelMenuButton(
    state: DockPanelState,
    layout: WorkspaceLayout,
    onWorkspaceAction: (WorkspaceLayout, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AeIconButton(AeIcons.More, "${state.panel.title} panel options", { open = true })
        AeMenu(
            expanded = open,
            onDismiss = { open = false },
            entries = buildList {
                if (!state.floating) {
                    add(
                        AeMenuEntry.Item("Move Up", {
                            onWorkspaceAction(layout.move(state.panel, -1), "Move ${state.panel.title} panel up")
                        }),
                    )
                    add(
                        AeMenuEntry.Item("Move Down", {
                            onWorkspaceAction(layout.move(state.panel, 1), "Move ${state.panel.title} panel down")
                        }),
                    )
                    add(
                        AeMenuEntry.Item("Float", {
                            onWorkspaceAction(
                                layout.update(state.panel) { it.copy(floating = true, expanded = true) },
                                "Float ${state.panel.title} panel",
                            )
                        }),
                    )
                } else {
                    add(
                        AeMenuEntry.Item("Dock", {
                            onWorkspaceAction(layout.update(state.panel) { it.copy(floating = false) }, "Dock ${state.panel.title} panel")
                        }),
                    )
                }
                add(AeMenuEntry.Divider)
                add(
                    AeMenuEntry.Item("Close Panel", {
                        onWorkspaceAction(layout.update(state.panel) { it.copy(visible = false) }, "Close ${state.panel.title} panel")
                    }, icon = AeIcons.Close),
                )
            },
        )
    }
}

/** Right dock: visible, docked panels in workspace order; Layers (if expanded) takes the remaining height. */
@Composable
fun DockColumn(
    layout: WorkspaceLayout,
    onLayout: (WorkspaceLayout) -> Unit,
    content: PanelContent,
    modifier: Modifier = Modifier,
    onWorkspaceAction: (WorkspaceLayout, String) -> Unit = { next, _ -> onLayout(next) },
) {
    val docked = layout.panels.filter { it.visible && !it.floating }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Ae.colors.surface1)) {
        // Tall panels (Character, Brush Settings…) scroll inside a capped height instead of pushing
        // the panels below them off the dock.
        val cap = maxHeight * 0.6f
        Column(Modifier.fillMaxSize()) {
            docked.forEach { st ->
                val grow = st.panel == DockPanel.LAYERS && st.expanded
                AePanel(
                    st.panel.title,
                    modifier = if (grow) Modifier.weight(1f) else Modifier,
                    expanded = st.expanded,
                    onExpandedChange = { e ->
                        onWorkspaceAction(
                            layout.update(st.panel) {
                                it.copy(expanded = e)
                            },
                            "${if (e) "Expand" else "Collapse"} ${st.panel.title} panel",
                        )
                    },
                    actions = { PanelMenuButton(st, layout, onWorkspaceAction) },
                ) {
                    if (grow) {
                        content(st.panel)
                    } else {
                        Column(Modifier.heightIn(max = cap).verticalScroll(androidx.compose.foundation.rememberScrollState())) { content(st.panel) }
                    }
                }
            }
        }
    }
}

/** 6dp grab strip on the dock's leading edge; drag to resize between 240 and 480dp. */
@Composable
fun DockResizeHandle(
    layout: WorkspaceLayout,
    onLayout: (WorkspaceLayout) -> Unit,
    onWorkspaceAction: (WorkspaceLayout, String) -> Unit = { next, _ -> onLayout(next) },
) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(layout)
    var dragLayout by remember { mutableStateOf<WorkspaceLayout?>(null) }
    Box(
        Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .semantics { contentDescription = "Resize panels" }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        dragLayout?.let { onWorkspaceAction(it, "Resize panel dock") }
                        dragLayout = null
                    },
                    onDragCancel = { dragLayout = null },
                ) { change, drag ->
                    change.consume()
                    val base = dragLayout ?: current
                    val dx = with(density) { drag.x.toDp().value }
                    val next = base.withDockWidth(base.dockWidthDp - dx)
                    dragLayout = next
                    onLayout(next)
                }
            },
    ) {
        AeDivider(Modifier.fillMaxHeight().align(Alignment.Center), vertical = true)
    }
}

/** Floating panels over the canvas; drag the header strip to move. */
@Composable
fun FloatingPanels(
    layout: WorkspaceLayout,
    onLayout: (WorkspaceLayout) -> Unit,
    content: PanelContent,
    onWorkspaceAction: (WorkspaceLayout, String) -> Unit = { next, _ -> onLayout(next) },
) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(layout)
    layout.panels.filter { it.visible && it.floating }.forEach { st ->
        var dragLayout by remember(st.panel) { mutableStateOf<WorkspaceLayout?>(null) }
        Column(
            Modifier
                .offset { with(density) { IntOffset(st.floatX.dp.roundToPx(), st.floatY.dp.roundToPx()) } }
                .width(280.dp)
                .heightIn(max = 460.dp)
                .background(Ae.colors.surface1, Ae.shapes.popover)
                .border(1.dp, Ae.colors.lineStrong, Ae.shapes.popover)
                .pointerInput(st.panel) {
                    detectDragGestures(
                        onDragEnd = {
                            dragLayout?.let { onWorkspaceAction(it, "Move ${st.panel.title} panel") }
                            dragLayout = null
                        },
                        onDragCancel = { dragLayout = null },
                    ) { change, drag ->
                        // Only the header row (top 28dp) moves the panel.
                        if (change.position.y > with(density) { 28.dp.toPx() }) return@detectDragGestures
                        change.consume()
                        val base = dragLayout ?: current
                        val dx = with(density) { drag.x.toDp().value }
                        val dy = with(density) { drag.y.toDp().value }
                        val next = base.update(st.panel) {
                            it.copy(floatX = (it.floatX + dx).coerceAtLeast(0f), floatY = (it.floatY + dy).coerceAtLeast(0f))
                        }
                        dragLayout = next
                        onLayout(next)
                    }
                },
        ) {
            AePanel(
                st.panel.title,
                expanded = st.expanded,
                onExpandedChange = { e ->
                    onWorkspaceAction(
                        current.update(st.panel) {
                            it.copy(expanded = e)
                        },
                        "${if (e) "Expand" else "Collapse"} ${st.panel.title} panel",
                    )
                },
                actions = { PanelMenuButton(st, layout, onWorkspaceAction) },
            ) { content(st.panel) }
        }
    }
}

/**
 * Medium windows: a vertical icon rail; one panel at a time opens as an overlay.
 */
@Composable
fun DockRail(layout: WorkspaceLayout, openPanel: DockPanel?, onOpen: (DockPanel?) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(44.dp).fillMaxHeight().background(Ae.colors.surface1).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        layout.panels.filter { it.visible }.forEach { st ->
            AeToggleIconButton(
                st.panel.icon,
                st.panel.title,
                checked = openPanel == st.panel,
                onCheckedChange = { on -> onOpen(if (on) st.panel else null) },
            )
        }
    }
}

/** Overlay used by the rail (medium) and the bottom sheet (compact). */
@Composable
fun PanelOverlay(panel: DockPanel, onClose: () -> Unit, content: PanelContent, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Ae.colors.surface1, Ae.shapes.popover)
            .border(1.dp, Ae.colors.lineStrong, Ae.shapes.popover),
    ) {
        AePanel(panel.title, actions = { AeIconButton(AeIcons.Close, "Close ${panel.title}", onClose) }) { content(panel) }
    }
}

/** Compact windows: tools and panel buttons live in a bottom strip; panels open as a bottom sheet. */
@Composable
fun CompactBottomBar(
    tools: @Composable () -> Unit,
    layout: WorkspaceLayout,
    openPanel: DockPanel?,
    onOpen: (DockPanel?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Ae.colors.surface1).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { tools() }
        AeDivider(Modifier.padding(horizontal = 4.dp).fillMaxHeight(), vertical = true)
        layout.panels.filter { it.visible }.forEach { st ->
            AeToggleIconButton(st.panel.icon, st.panel.title, checked = openPanel == st.panel, onCheckedChange = { on ->
                onOpen(if (on) st.panel else null)
            })
        }
    }
}

@Composable
fun FillBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
