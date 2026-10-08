package app.croptop.feature.editor.session

import app.croptop.core.agent.OpenDocuments
import app.croptop.core.data.DocumentNames
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ProofIntent
import app.croptop.core.model.ProofTarget
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.SoftProofSettings
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.files.ShareCache
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

class SoftProofViewModelTest {
    private object Recents : RecentDocumentsRepository {
        override fun observe(limit: Int) = flowOf(emptyList<RecentDocument>())
        override suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long) = Unit
        override suspend fun remove(uri: String) = Unit
        override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
    }

    private object Cache : ShareCache {
        override fun newFile(name: String) = java.io.File.createTempFile("s", name)
        override fun uriFor(file: java.io.File) = file.toURI().toString()
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): EditorViewModel {
        val engine = FakeEngine()
        return EditorViewModel(engine, Recents, Cache, OpenDocuments(engine), DocumentNames { null }, EditorRoute(newSpec = NewDocumentSpec("Proof", DocSize(400, 300))))
    }

    @Test
    fun `proof colors toggles on and off, and gamut warning turns the proof on when it is off`() {
        val vm = viewModel()
        val doc = vm.document.value!!
        assertNull(doc.softProof.value)
        assertTrue(vm.toggleProofColors())
        assertEquals(SoftProofSettings(), doc.softProof.value)
        assertTrue(vm.toggleGamutWarning())
        assertTrue(doc.softProof.value!!.gamutWarning)
        assertTrue(vm.toggleGamutWarning())
        assertEquals(false, doc.softProof.value!!.gamutWarning)
        assertTrue(vm.toggleProofColors())
        assertNull(doc.softProof.value)
        assertTrue(vm.toggleGamutWarning())
        assertEquals(SoftProofSettings(gamutWarning = true), doc.softProof.value)
    }

    @Test
    fun `proof setup applies at once and is what the toggles come back to`() {
        val vm = viewModel()
        val doc = vm.document.value!!
        val setup = SoftProofSettings(ProofTarget.ADOBE_RGB, 260f, ProofIntent.PERCEPTUAL, blackPointCompensation = false, gamutWarning = true)
        assertTrue(vm.setProofSetup(setup))
        assertEquals(setup, doc.softProof.value)
        assertEquals(setup, vm.proofSetupDefaults())
        vm.toggleProofColors() // off
        assertNull(doc.softProof.value)
        vm.toggleProofColors() // on again with the remembered device, without the warning
        assertEquals(setup.copy(gamutWarning = false), doc.softProof.value)
        assertEquals(setup.copy(gamutWarning = false), vm.proofSetupDefaults())
    }

    @Test
    fun `the ink limit stays in the printable range`() {
        assertFailsWith<IllegalArgumentException> { SoftProofSettings(inkLimitPercent = 120f) }
        assertFailsWith<IllegalArgumentException> { SoftProofSettings(inkLimitPercent = Float.NaN) }
        assertEquals(400f, SoftProofSettings(inkLimitPercent = 400f).inkLimitPercent)
    }
}
