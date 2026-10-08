package app.croptop.feature.editor.panels

import android.net.Uri
import app.croptop.core.data.AgentSettings
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.Swatch
import app.croptop.core.model.SwatchIo
import app.croptop.core.model.UiPreferences
import app.croptop.core.model.WorkspaceLayout
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SwatchesViewModelTest {
    private val files = mutableListOf<File>()
    private lateinit var prefs: SwatchPreferences
    private lateinit var viewModel: SwatchesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        prefs = SwatchPreferences()
        viewModel = SwatchesViewModel(prefs, RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        files.forEach { it.delete() }
        Dispatchers.resetMain()
    }

    @Test
    fun importReportsOnlyColorsThatFitThePalette() = runTest {
        val existing = List(SwatchIo.MAX_SWATCHES - 1) { swatch(it) }
        prefs.setSwatches(existing)
        val file = tempFile().apply { writeBytes(SwatchIo.writeAse(listOf(swatch(5000), swatch(5001)))) }

        assertEquals("Added 1 swatches", viewModel.import(Uri.fromFile(file)).await())
        assertEquals(existing + swatch(5000), prefs.swatches.first())
    }

    @Test
    fun fullPaletteReportsCapacityInsteadOfUnsavedColors() = runTest {
        val existing = List(SwatchIo.MAX_SWATCHES) { swatch(it) }
        prefs.setSwatches(existing)
        val file = tempFile().apply { writeBytes(SwatchIo.writeAse(listOf(swatch(5000)))) }

        assertEquals("Swatch palette is full", viewModel.import(Uri.fromFile(file)).await())
        assertEquals(existing, prefs.swatches.first())
    }

    @Test
    fun duplicateOnlyImportStillReportsNoNewColorsWhenFull() = runTest {
        val existing = List(SwatchIo.MAX_SWATCHES) { swatch(it) }
        prefs.setSwatches(existing)
        val file = tempFile().apply { writeBytes(SwatchIo.writeAse(listOf(existing.first()))) }

        assertEquals("No new colors in that file", viewModel.import(Uri.fromFile(file)).await())
        assertEquals(existing, prefs.swatches.first())
    }

    @Test
    fun exportUsesPersistedColorsWithoutAUiSubscriber() = runTest {
        val palette = listOf(swatch(5000), swatch(5001))
        prefs.setSwatches(palette)
        val file = tempFile()

        assertEquals("Exported 2 swatches", viewModel.export(Uri.fromFile(file)).await())
        assertEquals(palette, SwatchIo.parseAse(file.readBytes()))
    }

    private fun tempFile(): File = File.createTempFile("swatches", ".ase").also { files += it }

    private fun swatch(value: Int) = Swatch("Color $value", RgbaColor.fromArgb(0xff000000.toInt() or value))
}

private class SwatchPreferences : PreferencesRepository {
    override val swatches = MutableStateFlow<List<Swatch>?>(null)
    override val ui = flowOf(UiPreferences())
    override val workspace = flowOf(WorkspaceLayout.Essentials)
    override val customWorkspaces = flowOf(emptyList<WorkspaceLayout>())
    override val keymapJson = flowOf<String?>(null)
    override val onboardingSeenVersion = flowOf(0)
    override val agentSettings = flowOf(AgentSettings())

    override suspend fun setSwatches(swatches: List<Swatch>?) {
        this.swatches.value = swatches
    }

    override suspend fun updateUi(transform: (UiPreferences) -> UiPreferences) = Unit
    override suspend fun setWorkspace(layout: WorkspaceLayout) = Unit
    override suspend fun saveCustomWorkspace(layout: WorkspaceLayout) = Unit
    override suspend fun deleteCustomWorkspace(name: String) = Unit
    override suspend fun setKeymapJson(json: String?) = Unit
    override suspend fun setOnboardingSeen(versionCode: Int) = Unit
    override suspend fun setAgentSettings(settings: AgentSettings) = Unit
}
