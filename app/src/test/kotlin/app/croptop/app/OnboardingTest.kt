package app.croptop.app

import android.view.KeyEvent
import app.croptop.app.onboarding.OnboardingViewModel
import app.croptop.core.input.ActionId
import app.croptop.core.input.KeyChord
import app.croptop.core.input.ShortcutManager
import app.croptop.core.input.ShortcutScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingTest {
    @Test
    fun `simple keymap drops single-letter editor keys but keeps chords and library keys`() {
        val sm = ShortcutManager(OnboardingViewModel.simpleKeymap())
        assertNull(sm.resolve(KeyChord(KeyEvent.KEYCODE_B), ShortcutScope.EDITOR))
        assertNull(sm.resolve(KeyChord(KeyEvent.KEYCODE_X), ShortcutScope.EDITOR))
        assertEquals("Ctrl+Z", sm.label(ActionId("edit.undo")))
        assertEquals("P", sm.label(ActionId("library.pick")))
    }
}
