package app.croptop.feature.library.batch

import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.fake.FakeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BatchExportControllerTest {
    private class Output(val failFor: Set<String> = emptySet()) : BatchOutput {
        val created = mutableListOf<String>()
        override suspend fun create(folder: String, baseName: String, format: ExportFormat): ExportTarget.ContentUri? {
            if (baseName in failFor) return null
            created += "$baseName.${format.extension()}"
            return ExportTarget.ContentUri("content://out/$baseName")
        }
    }

    private fun items(vararg names: String) = names.map { BatchItem("content://in/$it", it) }

    @Test
    fun exportsEveryItemAndReportsTheOutcome() = runTest {
        val output = Output()
        val controller = BatchExportController(FakeEngine(), output).apply { useDispatcher(StandardTestDispatcher(testScheduler)) }
        assertTrue(controller.start(items("a.jpg", "b.dng"), ExportOptions(format = ExportFormat.JPEG), "content://folder"))
        assertFalse(controller.start(items("c.jpg"), ExportOptions(), "content://folder"), "a second batch must wait")
        advanceUntilIdle()
        val done = controller.state.value
        assertIs<BatchExportState.Finished>(done)
        assertEquals(2, done.succeeded)
        assertEquals(listOf("a.jpg", "b.jpg"), output.created)
        controller.dismiss()
        assertEquals(BatchExportState.Idle, controller.state.value)
    }

    @Test
    fun oneFailureDoesNotStopTheRest() = runTest {
        val controller = BatchExportController(FakeEngine(), Output(failFor = setOf("b"))).apply {
            useDispatcher(StandardTestDispatcher(testScheduler))
        }
        controller.start(items("a.jpg", "b.jpg", "c.jpg"), ExportOptions(), "content://folder")
        advanceUntilIdle()
        val done = controller.state.value as BatchExportState.Finished
        assertEquals(2, done.succeeded)
        assertEquals(listOf("b.jpg"), done.failures.map { it.name })
        assertFalse(done.cancelled)
    }

    @Test
    fun cancelStopsBeforeTheNextFile() = runTest {
        lateinit var controller: BatchExportController
        val output = object : BatchOutput {
            val created = mutableListOf<String>()
            override suspend fun create(folder: String, baseName: String, format: ExportFormat): ExportTarget.ContentUri {
                created += baseName
                controller.cancel() // user presses Cancel while the first file is being written
                return ExportTarget.ContentUri("content://out/$baseName")
            }
        }
        controller = BatchExportController(FakeEngine(), output).apply { useDispatcher(StandardTestDispatcher(testScheduler)) }
        controller.start(items("a.jpg", "b.jpg", "c.jpg"), ExportOptions(), "content://folder")
        advanceUntilIdle()
        val done = controller.state.value as BatchExportState.Finished
        assertTrue(done.cancelled)
        assertEquals(1, done.succeeded)
        assertEquals(listOf("a"), output.created)
    }

    @Test
    fun rawRecipeReachesTheEngineOpenOptions() = runTest {
        val engine = FakeEngine()
        val controller = BatchExportController(engine, Output()).apply { useDispatcher(StandardTestDispatcher(testScheduler)) }
        val recipe = app.croptop.core.model.DevelopSettings(basic = app.croptop.core.model.BasicTone(exposure = 1f))
        controller.start(listOf(BatchItem("content://in/a.dng", "a.dng", recipe)), ExportOptions(), "content://folder")
        advanceUntilIdle()
        assertEquals(recipe, engine.lastRawDevelopSettings)
    }

    @Test
    fun theLibraryMetadataReachesTheExportUnlessMetadataIsStripped() = runTest {
        val engine = FakeEngine()
        val controller = BatchExportController(engine, Output()).apply { useDispatcher(StandardTestDispatcher(testScheduler)) }
        val iptc = app.croptop.core.engine.api.IptcMetadata(title = "Bay", caption = "Evening", keywords = listOf("sunset"))
        val item = BatchItem("content://in/a.jpg", "a.jpg", iptc = iptc)
        controller.start(listOf(item), ExportOptions(), "content://folder")
        advanceUntilIdle()
        controller.dismiss()
        controller.start(listOf(item), ExportOptions(stripMetadata = true), "content://folder")
        advanceUntilIdle()
        assertEquals(listOf(iptc, null), engine.exportedOptions.map { it.iptc })
    }
}
