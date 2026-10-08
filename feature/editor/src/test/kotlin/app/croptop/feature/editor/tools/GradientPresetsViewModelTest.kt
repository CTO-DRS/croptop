package app.croptop.feature.editor.tools

import app.croptop.core.data.GradientRepository
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientPreset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class GradientPresetsViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful save update and delete invoke completion callbacks`() = runTest {
        val repository = FakeGradientRepository()
        val viewModel = GradientPresetsViewModel(repository)
        val completed = mutableListOf<String>()
        val preset = GradientPreset(1, "Ocean", ColorGradient.BlackToWhite)

        listOf(
            viewModel.save("Ocean", ColorGradient.BlackToWhite, onSaved = { completed += "save:${it.name}" }),
            viewModel.update(preset, "Sea", ColorGradient.BlackToWhite, onUpdated = { completed += "update" }),
            viewModel.delete(preset, onDeleted = { completed += "delete" }),
        ).joinAll()

        assertEquals(listOf("save:Ocean", "update", "delete"), completed)
    }

    @Test
    fun `repository failures and stale preset operations invoke failure callbacks`() = runTest {
        val repository = FakeGradientRepository().apply {
            saveFailure = IllegalArgumentException("Preset name already exists")
            updateResult = false
            deleteFailure = IllegalStateException("Storage is unavailable")
        }
        val viewModel = GradientPresetsViewModel(repository)
        val failures = mutableListOf<String>()
        val preset = GradientPreset(1, "Ocean", ColorGradient.BlackToWhite)

        listOf(
            viewModel.save("Ocean", ColorGradient.BlackToWhite, onFailure = failures::add),
            viewModel.update(preset, "Sea", ColorGradient.BlackToWhite, onFailure = failures::add),
            viewModel.delete(preset, onFailure = failures::add),
        ).joinAll()

        assertEquals(
            listOf("Preset name already exists", "Gradient preset is no longer available", "Storage is unavailable"),
            failures,
        )
    }
}

private class FakeGradientRepository : GradientRepository {
    override val customPresets: Flow<List<GradientPreset>> = emptyFlow()
    var saveFailure: Exception? = null
    var updateResult = true
    var deleteFailure: Exception? = null

    override suspend fun save(name: String, gradient: ColorGradient): GradientPreset {
        saveFailure?.let {
            throw it
        }
        return GradientPreset(1, name, gradient)
    }

    override suspend fun update(id: Long, name: String, gradient: ColorGradient): Boolean = updateResult

    override suspend fun delete(id: Long): Boolean {
        deleteFailure?.let {
            throw it
        }
        return true
    }
}
