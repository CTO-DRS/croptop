package app.croptop.feature.settings

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.ActionId
import app.croptop.core.input.KeyChord
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.input.ShortcutPreset
import app.croptop.core.model.UiPreferences
import app.croptop.core.model.WorkspaceLayout
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class FakePrefs : PreferencesRepository {
    override val ui = MutableStateFlow(UiPreferences())

    override suspend fun updateUi(transform: (UiPreferences) -> UiPreferences) {
        ui.value = transform(ui.value)
    }

    override val workspace: Flow<WorkspaceLayout> = MutableStateFlow(WorkspaceLayout.Essentials)

    override suspend fun setWorkspace(layout: WorkspaceLayout) = Unit

    override val customWorkspaces: Flow<List<WorkspaceLayout>> = MutableStateFlow(emptyList())

    override suspend fun saveCustomWorkspace(layout: WorkspaceLayout) = Unit

    override suspend fun deleteCustomWorkspace(name: String) = Unit

    val keymap = MutableStateFlow<String?>(null)
    override val keymapJson: Flow<String?> = keymap

    override suspend fun setKeymapJson(json: String?) {
        keymap.value = json
    }

    override val onboardingSeenVersion: Flow<Int> = MutableStateFlow(0)

    override suspend fun setOnboardingSeen(versionCode: Int) = Unit

    override val agentSettings: Flow<app.croptop.core.data.AgentSettings> = MutableStateFlow(app.croptop.core.data.AgentSettings())

    override suspend fun setAgentSettings(settings: app.croptop.core.data.AgentSettings) = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ShortcutsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun editorWithOneModifiedShortcut() {
        val prefs = FakePrefs()
        val vm = ShortcutsViewModel(ShortcutManager(), prefs)
        // Ctrl+Y for redo (Windows habit) — steals nothing.
        vm.assign(ActionId("edit.redo"), KeyChord(KeyEvent.KEYCODE_Y, ctrl = true))
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) { ShortcutsScreen(onBack = {}, viewModel = vm) }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/shortcuts_1280x800.png")
        kotlin.test.assertNotNull(prefs.keymap.value)
    }

    @Test
    fun reassigningAConflictMovesTheChord() {
        val vm = ShortcutsViewModel(ShortcutManager(), FakePrefs())
        val ctrlZ = KeyChord(KeyEvent.KEYCODE_Z, ctrl = true)
        vm.assign(ActionId("layer.new"), ctrlZ)
        assertEquals(listOf(ctrlZ), vm.keymap.value.chordsFor(ActionId("layer.new")))
        assertEquals(emptyList(), vm.keymap.value.chordsFor(ActionId("edit.undo")))
        vm.resetAll()
        assertEquals(listOf(ctrlZ), vm.keymap.value.chordsFor(ActionId("edit.undo")))
    }

    @Test
    fun selectingAffinityPresetAppliesAndPersistsThePreset() {
        val prefs = FakePrefs()
        val vm = ShortcutsViewModel(ShortcutManager(), prefs)

        vm.applyPreset(ShortcutPreset.AFFINITY_PHOTO)

        assertEquals(ShortcutPreset.AFFINITY_PHOTO, vm.presetFor(vm.keymap.value))
        assertEquals("K", vm.keymap.value.primaryChord(ActionId("tool.CLONE"))?.format())
        assertEquals(vm.keymap.value.toJson(), prefs.keymap.value)

        vm.applyPreset(ShortcutPreset.PHOTOSHOP)

        assertEquals(ShortcutPreset.PHOTOSHOP, vm.presetFor(vm.keymap.value))
        assertEquals(null, prefs.keymap.value)
    }
}
