package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class WorkspaceTest {
    @Test
    fun `presets contain every panel once`() {
        WorkspaceLayout.Presets.forEach { ws ->
            assertEquals(DockPanel.entries.toSet(), ws.panels.map { it.panel }.toSet(), ws.name)
            assertEquals(DockPanel.entries.size, ws.panels.size, ws.name)
        }
    }

    @Test
    fun `move clamps and reorders`() {
        val ws = WorkspaceLayout.Essentials
        assertEquals(DockPanel.COLOR, ws.move(DockPanel.COLOR, -1).panels[1].panel)
        assertEquals(ws, ws.move(DockPanel.HISTOGRAM, -1))
        assertEquals(DockPanel.NAVIGATOR, ws.move(DockPanel.NAVIGATOR, 10).panels.last().panel)
    }

    @Test
    fun `update, width clamp and json round trip`() {
        val ws = WorkspaceLayout.Essentials.update(DockPanel.HISTORY) { it.copy(visible = false) }.withDockWidth(9999f)
        assertFalse(ws.state(DockPanel.HISTORY).visible)
        assertEquals(WorkspaceLayout.MAX_DOCK_DP, ws.dockWidthDp)
        val json = Json.encodeToString(WorkspaceLayout.serializer(), ws)
        assertEquals(ws, Json.decodeFromString(WorkspaceLayout.serializer(), json))
    }

    @Test
    fun updateAppendsPanelsMissingFromOldLayouts() {
        val old = WorkspaceLayout("Mine", WorkspaceLayout.Essentials.panels.filter { it.panel != DockPanel.CHARACTER })
        assertFalse(old.state(DockPanel.CHARACTER).visible)
        val shown = old.update(DockPanel.CHARACTER) { it.copy(visible = true) }
        assertEquals(DockPanel.CHARACTER, shown.panels.last().panel)
        assertTrue(shown.state(DockPanel.CHARACTER).visible)
    }
}
