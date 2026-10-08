package app.croptop.feature.editor.tools

import androidx.compose.ui.input.key.Key
import androidx.lifecycle.ViewModelStore
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.NoRecents
import app.croptop.feature.editor.TempShareCache
import app.croptop.feature.editor.session.EditorViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

class MoveViewModelTest {
    private lateinit var viewModels: ViewModelStore
    private lateinit var vm: EditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModels = ViewModelStore()
        val engine = FakeEngine()
        vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Move test", size = DocSize(100, 100))),
        )
        viewModels.put("editor", vm)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `selected layer translation is one undoable history step`() {
        vm.newLayer(LayerKind.PIXEL)
        val before = vm.state.value.history.current
        val burst = MoveNudgeBurst(vm::translateSelected)

        repeat(3) { assertTrue(burst.keyDown(Key.DirectionRight, shift = false)) }
        assertEquals(before, vm.state.value.history.current)
        assertTrue(burst.keyUp(Key.DirectionRight))
        assertEquals(before + 1, vm.state.value.history.current)
        assertEquals("Move", vm.state.value.history.entries[vm.state.value.history.current].label)

        assertTrue(vm.undo() is CommandResult.Accepted)
        assertEquals(before, vm.state.value.history.current)
        assertTrue(vm.redo() is CommandResult.Accepted)
        assertEquals(before + 1, vm.state.value.history.current)
    }

    @Test
    fun `arrow burst translates the complete selected target set in one undoable step`() {
        vm.newLayer(LayerKind.PIXEL)
        val firstId = checkNotNull(vm.state.value.primary)
        vm.newLayer(LayerKind.PIXEL)
        val secondId = checkNotNull(vm.state.value.primary)
        assertTrue(vm.select(firstId))
        assertTrue(vm.select(secondId, additive = true))
        assertEquals(setOf(firstId, secondId), vm.state.value.selected)
        val commits = mutableListOf<Pair<Set<app.croptop.core.model.LayerId>, Pair<Float, Float>>>()
        val historyBefore = vm.state.value.history.current
        val burst = MoveNudgeBurst { dx, dy ->
            commits += vm.state.value.selected to (dx to dy)
            vm.translateSelected(dx, dy)
        }

        repeat(2) { assertTrue(burst.keyDown(Key.DirectionRight, shift = false)) }
        assertTrue(burst.keyUp(Key.DirectionRight))

        assertEquals(historyBefore + 1, vm.state.value.history.current)
        assertEquals(listOf(setOf(firstId, secondId) to (2f to 0f)), commits)
        assertEquals("Move", vm.state.value.history.entries[vm.state.value.history.current].label)
        assertTrue(vm.undo() is CommandResult.Accepted)
        assertEquals(historyBefore, vm.state.value.history.current)
        assertTrue(vm.redo() is CommandResult.Accepted)
        assertEquals(historyBefore + 1, vm.state.value.history.current)
    }
}
