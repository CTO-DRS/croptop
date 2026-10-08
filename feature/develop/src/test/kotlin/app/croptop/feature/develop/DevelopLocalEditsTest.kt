package app.croptop.feature.develop

import app.croptop.core.engine.api.Engine
import app.croptop.core.model.BasicTone
import app.croptop.core.model.Calibration
import app.croptop.core.model.DevelopSection
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.LocalMask
import app.croptop.core.model.MaskKind
import app.croptop.core.model.MaskPoint
import app.croptop.core.model.MaskStroke
import app.croptop.core.model.Spot
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

private fun noEngine(): Engine = Proxy.newProxyInstance(Engine::class.java.classLoader, arrayOf(Engine::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("Unexpected Engine call: ${method.name}")
} as Engine

/** View-model behaviour of masks, spot repairs, calibration and sync across photos. */
class DevelopLocalEditsTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: KeyedDevelopRepo = KeyedDevelopRepo(), uri: String = "content://x/1.raf") = DevelopViewModel(repo, noEngine(), uri)

    @Test
    fun `adding a mask selects it, and deleting keeps the selection pointing at the same mask`() = runTest {
        val vm = vm()
        vm.addMask(MaskKind.LINEAR)
        vm.addMask(MaskKind.RADIAL)
        vm.addMask(MaskKind.BRUSH)
        assertEquals(3, vm.settings.value.masks.size)
        assertEquals(2, vm.selectedMask.value)
        vm.deleteMask(0)
        assertEquals(1, vm.selectedMask.value)
        assertEquals(MaskKind.BRUSH, vm.settings.value.masks[vm.selectedMask.value!!].components.single().kind)
        vm.deleteMask(1)
        assertNull(vm.selectedMask.value)
        vm.undo()
        assertEquals(2, vm.settings.value.masks.size)
    }

    @Test
    fun `mask edits are single history steps and previews are not`() = runTest {
        val vm = vm()
        vm.addMask(MaskKind.RADIAL)
        val steps = vm.history.value.size
        vm.previewMask(0) { it.copy(amount = 0.2f) }
        vm.previewMask(0) { it.copy(amount = 0.4f) }
        assertEquals(steps, vm.history.value.size)
        vm.commit("Mask amount")
        assertEquals(steps + 1, vm.history.value.size)
        assertEquals(0.4f, vm.settings.value.masks[0].amount, 1e-5f)
        vm.setMask(0, "Invert mask") { it.copy(invert = true) }
        assertTrue(vm.settings.value.masks[0].invert)
    }

    @Test
    fun `brush strokes go to the brush component and an erase stroke is recorded as such`() = runTest {
        val vm = vm()
        vm.addMask(MaskKind.LINEAR)
        vm.addStroke(0, MaskStroke(listOf(MaskPoint(0.1f, 0.1f), MaskPoint(0.2f, 0.2f))))
        val mask: LocalMask = vm.settings.value.masks[0]
        assertEquals(listOf(MaskKind.LINEAR, MaskKind.BRUSH), mask.components.map { it.kind })
        vm.addStroke(0, MaskStroke(listOf(MaskPoint(0.3f, 0.3f)), erase = true))
        val brush = vm.settings.value.masks[0].components[1]
        assertEquals(listOf(false, true), brush.strokes.map { it.erase })
        assertEquals("Erase mask stroke", vm.history.value.last().label)
    }

    @Test
    fun `spots are added with an automatic source, edited and removed`() = runTest {
        val vm = vm()
        vm.addSpot(0.4f, 0.5f, 1.5f)
        val spot = vm.settings.value.spots.single()
        assertEquals(0.4f, spot.x, 1e-5f)
        assertTrue(spot.sourceX > spot.x, "the source is picked to the right when there is room")
        assertEquals(0, vm.selectedSpot.value)
        vm.previewSpot(0) { it.copy(x = 0.45f) }
        vm.commit("Move spot")
        vm.setSpot(0, "Clone spot") { it.copy(heal = false) }
        assertEquals(0.45f, vm.settings.value.spots[0].x, 1e-5f)
        assertTrue(!vm.settings.value.spots[0].heal)
        vm.deleteSpot(0)
        assertTrue(vm.settings.value.spots.isEmpty())
        assertNull(vm.selectedSpot.value)
        vm.deleteSpot(5)
        assertTrue(vm.settings.value.spots.isEmpty())
    }

    @Test
    fun `no more than the maximum number of spots are placed`() = runTest {
        val vm = vm()
        repeat(Spot.MAX_SPOTS + 3) { vm.addSpot(0.1f + it * 0.01f, 0.5f, 1f) }
        assertEquals(Spot.MAX_SPOTS, vm.settings.value.spots.size)
    }

    @Test
    fun `white balance picking sets a custom balance only for a usable pixel`() = runTest {
        val vm = vm()
        assertTrue(!vm.pickWhiteBalance(0, 0), "there is no preview frame yet")
    }

    @Test
    fun `sync applies the chosen sections to the other photos and leaves the rest alone`() = runTest {
        val repo = KeyedDevelopRepo()
        val other = DevelopSettings(basic = BasicTone(exposure = -1f), calibration = Calibration(redHue = 5f))
        repo.save("content://x/2.raf", other)
        val vm = vm(repo)
        vm.preview { it.copy(basic = BasicTone(exposure = 2f), calibration = Calibration(redHue = 40f)) }
        vm.commit("Edit")
        vm.syncTo(listOf("content://x/1.raf", "content://x/2.raf", "content://x/3.raf"), setOf(DevelopSection.BASIC))
        assertEquals(2, vm.syncResult.value, "two other photos are updated, not the source itself")
        assertEquals(2f, repo.byKey.getValue("content://x/2.raf").value.basic.exposure, 1e-5f)
        assertEquals(5f, repo.byKey.getValue("content://x/2.raf").value.calibration.redHue, 1e-5f)
        assertEquals(2f, repo.byKey.getValue("content://x/3.raf").value.basic.exposure, 1e-5f)
    }

    @Test
    fun `syncing settings a photo already has changes nothing`() = runTest {
        val repo = KeyedDevelopRepo()
        val vm = vm(repo)
        vm.syncTo(listOf("content://x/1.raf", "content://x/2.raf"), setOf(DevelopSection.BASIC))
        assertEquals(0, vm.syncResult.value)
    }

    @Test
    fun `a snapshot restores an earlier recipe as one undoable step`() = runTest {
        val vm = vm()
        vm.preview { it.copy(basic = BasicTone(exposure = 1f)) }
        vm.commit("Exposure")
        vm.createSnapshot()
        val snapshot = vm.snapshots.first { it.isNotEmpty() }.single()
        assertEquals("Snapshot 1", snapshot.name)
        vm.preview { it.copy(basic = BasicTone(exposure = -2f)) }
        vm.commit("Exposure")
        vm.applySnapshot(snapshot)
        assertEquals(1f, vm.settings.value.basic.exposure, 1e-5f)
        vm.undo()
        assertEquals(-2f, vm.settings.value.basic.exposure, 1e-5f)
        vm.deleteSnapshot(snapshot.id)
        assertTrue(vm.snapshots.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `renaming a mask is one history step and brush settings default to a soft full flow stroke`() = runTest {
        val vm = vm()
        vm.addMask(MaskKind.BRUSH)
        vm.setMask(0, "Rename mask") { it.copy(name = "Sky") }
        assertEquals("Sky", vm.settings.value.masks[0].name)
        assertEquals("Rename mask", vm.history.value.last().label)
        assertEquals(0.05f, vm.brushSize.value, 1e-6f)
        assertEquals(0.5f, vm.brushFeather.value, 1e-6f)
        assertEquals(1f, vm.brushFlow.value, 1e-6f)
        assertTrue(DevelopViewModel.MASK_OVERLAY_COLORS.map { it.first }.containsAll(listOf("Red", "Yellow", "Green", "Blue")))
    }
}
