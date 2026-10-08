package app.croptop.core.input

import android.view.KeyEvent
import app.croptop.core.model.ToolGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeymapTest {
    private val km = Keymap.default()
    private val sm = ShortcutManager(km)

    @Test
    fun `chords parse and format round trip`() {
        listOf("Ctrl+Shift+Z", "Alt+[", "Ctrl++", "Ctrl+,", "Shift+Backspace", "Ctrl+Alt+Shift+E", "F", "\\").forEach {
            val c = KeyChord.parse(it)!!
            assertEquals(c, KeyChord.parse(c.format()), it)
        }
        assertEquals(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true, shift = true), KeyChord.parse("ctrl+shift+z"))
        assertNull(KeyChord.parse("Hyper+Z"))
        assertNull(KeyChord.parse("Ctrl+Nope"))
    }

    @Test
    fun `default keymap is clean`() {
        assertTrue(km.problems.isEmpty(), km.problems.joinToString())
        assertTrue(km.conflicts().isEmpty(), km.conflicts().joinToString())
        assertTrue(km.bindings.size > 100)
    }

    @Test
    fun `every tool group has a key`() {
        val unbound = ToolGroup.entries.filter { km.chordsFor(toolAction(it)).isEmpty() }
        // Focus and Liquify have no tool shortcuts in Photoshop; Liquify is opened from the Filter menu.
        assertEquals(listOf(ToolGroup.FOCUS, ToolGroup.LIQUIFY), unbound)
    }

    @Test
    fun `scopes separate editor develop and library letters`() {
        val g = KeyChord(KeyEvent.KEYCODE_G)
        assertEquals(ShortcutHit.Action(ActionId("tool.FILL")), sm.resolve(g, ShortcutScope.EDITOR))
        assertEquals(ShortcutHit.Action(ActionId("develop.grid")), sm.resolve(g, ShortcutScope.DEVELOP))
        assertNull(sm.resolve(g, ShortcutScope.LIBRARY))
        // Global actions resolve everywhere.
        val ctrlK = KeyChord(KeyEvent.KEYCODE_K, ctrl = true)
        ShortcutScope.entries.forEach { assertEquals(ShortcutHit.Action(ActionId("app.palette")), sm.resolve(ctrlK, it)) }
    }

    @Test
    fun `shift plus tool key cycles`() {
        assertEquals(ShortcutHit.CycleTool(ToolGroup.MARQUEE), sm.resolve(KeyChord(KeyEvent.KEYCODE_M, shift = true), ShortcutScope.EDITOR))
        // Shift+[ is an explicit binding and wins over cycling.
        assertEquals(
            ShortcutHit.Action(ActionId("brush.hardnessDown")),
            sm.resolve(KeyChord(KeyEvent.KEYCODE_LEFT_BRACKET, shift = true), ShortcutScope.EDITOR),
        )
    }

    @Test
    fun `brackets remain bound to brush size actions`() {
        assertEquals(
            ShortcutHit.Action(ActionId("brush.sizeDown")),
            sm.resolve(KeyChord(KeyEvent.KEYCODE_LEFT_BRACKET), ShortcutScope.EDITOR),
        )
        assertEquals(
            ShortcutHit.Action(ActionId("brush.sizeUp")),
            sm.resolve(KeyChord(KeyEvent.KEYCODE_RIGHT_BRACKET), ShortcutScope.EDITOR),
        )
    }

    @Test
    fun `last filter shortcut is available and conflict free`() {
        assertEquals(KeyChord.parse("Ctrl+Alt+F"), km.primaryChord(ActionId("filter.last")))
        assertEquals("Last Filter", Actions[ActionId("filter.last")]?.label)
        assertTrue(km.conflicts().isEmpty(), km.conflicts().joinToString())
    }

    @Test
    fun `text input suppresses single keys only`() {
        assertNull(sm.resolve(KeyChord(KeyEvent.KEYCODE_B), ShortcutScope.EDITOR, textInputActive = true))
        assertEquals(
            ShortcutHit.Action(ActionId("edit.undo")),
            sm.resolve(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true), ShortcutScope.EDITOR, textInputActive = true),
        )
    }

    @Test
    fun `rebind and json round trip`() {
        val remapped = km.rebind(ActionId("edit.redo"), listOf(KeyChord(KeyEvent.KEYCODE_Y, ctrl = true)))
        val back = Keymap.fromJson(remapped.toJson())
        assertEquals(remapped.bindings, back.bindings)
        sm.setKeymap(remapped)
        assertEquals("Ctrl+Y", sm.label(ActionId("edit.redo")))
    }

    @Test
    fun `conflict detection`() {
        val bad = km.rebind(ActionId("layer.new"), listOf(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true)))
        val c = bad.conflicts().single()
        assertEquals(setOf(ActionId("layer.new"), ActionId("edit.undo")), c.actions.toSet())
    }

    @Test
    fun `differences and conflict lookup`() {
        val ctrlY = KeyChord(KeyEvent.KEYCODE_Y, ctrl = true)
        val remapped = km.rebind(ActionId("edit.redo"), listOf(ctrlY))
        assertEquals(setOf(ActionId("edit.redo")), remapped.differencesFrom(km))
        assertEquals(listOf(ActionId("edit.undo")), km.actionsUsing(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true), ActionId("layer.new")))
        // Develop "G" doesn't clash with the editor's G.
        assertTrue(km.actionsUsing(KeyChord(KeyEvent.KEYCODE_G), ActionId("develop.grid")).isEmpty())
    }

    @Test
    fun `Affinity preset applies documented shortcuts without scope conflicts`() {
        val affinity = km.withAffinityPhoto2Shortcuts()

        assertEquals(KeyChord.parse("K"), affinity.primaryChord(ActionId("tool.CLONE")))
        assertEquals(KeyChord.parse("Ctrl+Alt+Shift+S"), affinity.primaryChord(ActionId("app.exportAs")))
        assertEquals(KeyChord.parse("F5"), affinity.primaryChord(ActionId("edit.fill")))
        assertEquals(km.chordsFor(ActionId("layer.mergeDown")), affinity.chordsFor(ActionId("layer.mergeDown")))
        assertEquals(km.chordsFor(ActionId("layer.mergeVisible")), affinity.chordsFor(ActionId("layer.mergeVisible")))
        assertEquals(km.chordsFor(ActionId("layer.stampVisible")), affinity.chordsFor(ActionId("layer.stampVisible")))
        assertTrue(affinity.conflicts().isEmpty(), affinity.conflicts().toString())
    }
}
