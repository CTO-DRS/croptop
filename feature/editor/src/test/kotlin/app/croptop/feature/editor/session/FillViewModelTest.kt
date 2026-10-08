package app.croptop.feature.editor.session

import androidx.lifecycle.ViewModelStore
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.NoRecents
import app.croptop.feature.editor.TempShareCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

class FillViewModelTest {
    private lateinit var vm: EditorViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val engine = FakeEngine()
        store = ViewModelStore()
        vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        store.put("fill", vm)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun contentAwareRejectionIsReturnedAndShownWithoutLosingEngineReason() = runTest {
        val reason = "Content-Aware Fill needs a selection"

        assertEquals(
            CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, reason),
            vm.fillJob(FillSource.ContentAware(), action = "Content-Aware Fill").await(),
        )
        assertEquals(reason, vm.state.value.message)
        assertEquals("Content-Aware Fill", vm.state.value.actionFeedback?.action)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        assertEquals(reason, vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun genericFillCallersKeepTheDefaultActionLabel() = runTest {
        vm.fillJob(FillSource.Solid(RgbaColor.White)).await()

        assertEquals("Fill layer", vm.state.value.actionFeedback?.action)
        assertEquals("Fill layer rendered", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun fillWithForegroundAndBackgroundKeepTheirMenuActionLabels() {
        vm.setForeground(RgbaColor(0.9f, 0.2f, 0.1f))
        vm.setBackground(RgbaColor(0.1f, 0.3f, 0.8f))

        vm.fillWith(foreground = true)
        assertEquals("Fill with foreground color", vm.state.value.actionFeedback?.action)
        assertEquals("Fill with foreground color rendered", vm.state.value.actionFeedback?.statusText)

        vm.fillWith(foreground = false)
        assertEquals("Fill with background color", vm.state.value.actionFeedback?.action)
        assertEquals("Fill with background color rendered", vm.state.value.actionFeedback?.statusText)
    }

    @Test
    fun acknowledgedFillCancellationUsesCanceledFeedback() = runTest {
        val canceled = CommandResult.Rejected(RejectionCode.CANCELLED, "Canceled")
        val testVm = viewModelWithFillResult(canceled)

        assertEquals(canceled, testVm.fillJob(FillSource.ContentAware(), action = "Content-Aware Fill").await())

        assertEquals(ActionFeedbackPhase.CANCELED, testVm.state.value.actionFeedback?.phase)
        assertEquals("Content-Aware Fill", testVm.state.value.actionFeedback?.action)
        assertEquals("Content-Aware Fill canceled", testVm.state.value.actionFeedback?.statusText)
        assertEquals(RejectionCode.CANCELLED, testVm.state.value.actionFeedback?.rejectionCode)
        assertEquals(null, testVm.state.value.message)
    }

    @Test
    fun acceptedFillResultWinsOverCancellationRequest() = runTest {
        val accepted = CommandResult.Accepted(321)
        val testVm = viewModelWithFillResult(accepted)
        val job = testVm.fillJob(FillSource.ContentAware(), action = "Content-Aware Fill")

        job.cancel()
        assertEquals(accepted, job.await())

        assertEquals("Content-Aware Fill", testVm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.ACCEPTED, testVm.state.value.actionFeedback?.phase)
        assertEquals(321, testVm.state.value.actionFeedback?.engineSequence)
    }

    private fun viewModelWithFillResult(result: CommandResult): EditorViewModel {
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override fun newDocument(spec: NewDocumentSpec): DocumentHandle {
                val document = fake.newDocument(spec)
                return object : DocumentHandle by document {
                    override fun fillJob(
                        source: FillSource,
                        opacity: Float,
                        blendMode: BlendMode,
                        preserveTransparency: Boolean,
                    ): EngineJob = EngineJob.completed(result)
                }
            }
        }
        val testVm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        store.put("fill-terminal", testVm)
        return testVm
    }
}
