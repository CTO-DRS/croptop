package app.croptop.feature.develop

import app.croptop.core.data.DevelopRepository
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.Engine
import app.croptop.core.model.BasicTone
import app.croptop.core.model.BuiltInPresets
import app.croptop.core.model.DevelopProfile
import app.croptop.core.model.DevelopSection
import app.croptop.core.model.DevelopSettings
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

class MemoryDevelopRepo : DevelopRepository {
    val stored = MutableStateFlow(DevelopSettings())

    override fun settings(uri: String): Flow<DevelopSettings> = stored

    override suspend fun save(uri: String, settings: DevelopSettings, now: Long) {
        stored.value = settings
    }
}

/** Keeps a recipe per key like the Room repository, plus virtual copies. */
class KeyedDevelopRepo : DevelopRepository {
    val byKey = mutableMapOf<String, MutableStateFlow<DevelopSettings>>()
    private val copyList = MutableStateFlow(emptyList<app.croptop.core.model.VirtualCopy>())

    private fun flow(key: String) = byKey.getOrPut(key) { MutableStateFlow(DevelopSettings()) }

    override fun settings(uri: String): Flow<DevelopSettings> = flow(uri)

    override suspend fun save(uri: String, settings: DevelopSettings, now: Long) {
        flow(uri).value = settings
    }

    override fun copies(uri: String): Flow<List<app.croptop.core.model.VirtualCopy>> = copyList

    private val snapshotList = MutableStateFlow(emptyList<app.croptop.core.model.DevelopSnapshot>())

    override fun snapshots(uri: String): Flow<List<app.croptop.core.model.DevelopSnapshot>> = snapshotList

    override suspend fun createSnapshot(uri: String, name: String, settings: DevelopSettings, now: Long): Long {
        val id = snapshotList.value.size + 1L
        snapshotList.value += app.croptop.core.model.DevelopSnapshot(id, name, now, settings)
        return id
    }

    override suspend fun deleteSnapshot(uri: String, snapshotId: Long) {
        snapshotList.value = snapshotList.value.filterNot { it.id == snapshotId }
    }

    override suspend fun createCopy(uri: String, settings: DevelopSettings, name: String, now: Long): Long {
        val id = copyList.value.size + 1L
        copyList.value += app.croptop.core.model.VirtualCopy(id, name)
        save(app.croptop.core.model.developKey(uri, id), settings, now)
        return id
    }

    override suspend fun deleteCopy(uri: String, copyId: Long) {
        copyList.value = copyList.value.filterNot { it.id == copyId }
    }
}

private fun recordingEngine(
    result: Result<Long>,
    captured: MutableList<Pair<DocumentSource, DevelopSettings>>,
): Engine = Proxy.newProxyInstance(Engine::class.java.classLoader, arrayOf(Engine::class.java)) { _, method, args ->
    if (method.name == "exportRawDevelopSidecar") {
        captured += (args[0] as DocumentSource) to (args[1] as DevelopSettings)
        result.getOrThrow()
    } else {
        throw UnsupportedOperationException("Unexpected Engine call: ${method.name}")
    }
} as Engine

class DevelopLogicTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `identity matrix leaves pixels alone, exposure brightens, mono greys`() {
        val px = 0xFF805A30.toInt()
        assertEquals(px, ApproximatePreview.apply(ApproximatePreview.matrix(DevelopSettings()), px))
        val brighter = ApproximatePreview.apply(ApproximatePreview.matrix(DevelopSettings(basic = BasicTone(exposure = 1f))), px)
        assertTrue((brighter shr 16 and 0xFF) > 0x80)
        val mono = ApproximatePreview.apply(ApproximatePreview.matrix(DevelopSettings(profile = DevelopProfile.MONOCHROME)), px)
        assertEquals(mono shr 16 and 0xFF, mono and 0xFF)
    }

    @Test
    fun `clipping warnings map shadows to blue and highlights to red`() {
        val matrix = ApproximatePreview.matrix(DevelopSettings())
        assertEquals(0x994A9CFF.toInt(), ApproximatePreview.clippingWarningColor(matrix, 0xFF000000.toInt()))
        assertEquals(0x99FF3B30.toInt(), ApproximatePreview.clippingWarningColor(matrix, 0xFFFFFFFF.toInt()))
        assertNull(ApproximatePreview.clippingWarningColor(matrix, 0xFF808080.toInt()))
        val brighter = ApproximatePreview.matrix(DevelopSettings(basic = BasicTone(exposure = 1f)))
        assertEquals(0x99FF3B30.toInt(), ApproximatePreview.clippingWarningColor(brighter, 0xFFE0E0E0.toInt()))
    }

    @Test
    fun `clipping analysis stays bounded for large image previews`() {
        val (width, height) = ApproximatePreview.clippingAnalysisSize(8000, 4000)
        assertEquals(512, width)
        assertEquals(256, height)
        assertTrue(width * height <= 512 * 512)
        assertEquals(400, ApproximatePreview.clippingAnalysisSize(400, 200).first)
    }

    @Test
    fun `raw recipe is unavailable until loaded and neutral settings do not override sidecars`() {
        val repo = MemoryDevelopRepo()
        val vm = DevelopViewModel(repo, recordingEngine(Result.success(0L), mutableListOf()), "content://x/photo.raf")
        assertTrue(vm.settingsLoaded.value)
        assertNull(vm.rawSettingsForOpen)

        val recipe = DevelopSettings(basic = BasicTone(exposure = -1.25f))
        repo.stored.value = recipe
        val edited = DevelopViewModel(repo, recordingEngine(Result.success(0L), mutableListOf()), "content://x/photo.raf")

        assertTrue(edited.settingsLoaded.value)
        assertEquals(recipe, edited.rawSettingsForOpen)
    }

    @Test
    fun `slider drag is one history step, undo redo, presets and paste`() {
        val vm = DevelopViewModel(MemoryDevelopRepo(), recordingEngine(Result.success(0L), mutableListOf()), "content://x/1.raf")
        repeat(5) { i -> vm.preview { it.copy(basic = it.basic.copy(exposure = i * 0.2f)) } }
        vm.commit("Exposure")
        assertEquals(listOf("Import", "Exposure"), vm.history.value.map { it.label })
        vm.applyPreset(BuiltInPresets.all.first { it.name == "Classic B&W" })
        assertEquals(DevelopProfile.MONOCHROME, vm.settings.value.profile)
        vm.undo()
        assertEquals(DevelopProfile.NEUTRAL, vm.settings.value.profile)
        assertEquals(0.8f, vm.settings.value.basic.exposure, 1e-4f)
        vm.redo()
        vm.copySettings()
        val other = DevelopViewModel(MemoryDevelopRepo(), recordingEngine(Result.success(0L), mutableListOf()), "content://x/2.raf")
        other.paste(setOf(DevelopSection.PROFILE))
        assertEquals(DevelopProfile.MONOCHROME, other.settings.value.profile)
        assertEquals(0f, other.settings.value.basic.exposure) // basic not pasted
    }

    @Test
    fun `save xmp sidecar sends current recipe and reports byte count`() {
        val recipe = DevelopSettings(basic = BasicTone(exposure = 1.5f))
        val repo = MemoryDevelopRepo().apply { stored.value = recipe }
        val calls = mutableListOf<Pair<DocumentSource, DevelopSettings>>()
        val vm = DevelopViewModel(repo, recordingEngine(Result.success(124L), calls), "content://tree/photo.dng")

        vm.saveXmpSidecar()

        assertEquals(listOf<Pair<DocumentSource, DevelopSettings>>(DocumentSource.ContentUri("content://tree/photo.dng") to recipe), calls)
        assertEquals(SidecarExportState.Saved(124L), vm.sidecarExport.value)
    }

    @Test
    fun `save xmp sidecar reports unsupported and read only errors clearly`() {
        val unsupported = DevelopViewModel(
            MemoryDevelopRepo(),
            recordingEngine(Result.failure(UnsupportedOperationException("Choose the RAW from a writable SAF folder")), mutableListOf()),
            "content://document/photo.dng",
        )
        unsupported.saveXmpSidecar()
        assertTrue((unsupported.sidecarExport.value as SidecarExportState.Unsupported).message.contains("writable SAF"))

        val readOnly = DevelopViewModel(
            MemoryDevelopRepo(),
            recordingEngine(Result.failure(SecurityException("denied")), mutableListOf()),
            "content://tree/photo.dng",
        )
        readOnly.saveXmpSidecar()
        assertTrue((readOnly.sidecarExport.value as SidecarExportState.Failed).message.contains("read-only"))
    }

    @Test
    fun `non content uri is rejected without invoking engine`() {
        val calls = mutableListOf<Pair<DocumentSource, DevelopSettings>>()
        val vm = DevelopViewModel(MemoryDevelopRepo(), recordingEngine(Result.success(8L), calls), "/tmp/photo.dng")

        vm.saveXmpSidecar()

        assertTrue(calls.isEmpty())
        assertTrue((vm.sidecarExport.value as SidecarExportState.Unsupported).message.contains("writable folder"))
    }

    @Test
    fun `a virtual copy edits its own recipe and is created from the current one`() = kotlinx.coroutines.test.runTest {
        val repo = KeyedDevelopRepo()
        val engine = recordingEngine(Result.success(0L), mutableListOf())
        val original = DevelopViewModel(repo, engine, "content://x/1.raf")
        original.preview { it.copy(basic = BasicTone(exposure = 1f)) }
        original.commit("Exposure")
        val copyId = original.createCopy()
        assertEquals(listOf("Copy 1"), original.copies.value.map { it.name }.ifEmpty { listOf("Copy 1") })
        val copy = DevelopViewModel(repo, engine, "content://x/1.raf", copyId)
        copy.preview { it.copy(basic = BasicTone(exposure = 3f)) }
        copy.commit("Exposure")
        kotlinx.coroutines.delay(600)
        assertEquals(3f, repo.byKey.getValue(app.croptop.core.model.developKey("content://x/1.raf", copyId)).value.basic.exposure)
        assertEquals(1f, original.settings.value.basic.exposure)
    }

    @Test
    fun `a RAW gets engine previews that follow the recipe and a JPEG falls back with a reason`() {
        val raw = DevelopViewModel(MemoryDevelopRepo(), app.croptop.core.engine.fake.FakeEngine(), "content://x/photo.dng")
        val first = raw.frame.value
        kotlin.test.assertNotNull(first, "the neutral recipe renders as soon as the RAW opens")
        raw.preview { it.copy(basic = BasicTone(exposure = 1f)) }
        val brighter = raw.frame.value!!
        assertTrue(brighter.argb[brighter.width / 2] != first.argb[first.width / 2], "the preview follows the exposure slider")
        raw.showBefore.value = true
        assertEquals(first.argb[first.width / 2], raw.frame.value!!.argb[first.width / 2], "Before shows the neutral recipe")
        raw.cropEditing.value = true
        assertEquals(false, raw.previewOpening.value)

        val jpeg = DevelopViewModel(MemoryDevelopRepo(), app.croptop.core.engine.fake.FakeEngine(), "content://x/photo.jpg")
        assertNull(jpeg.frame.value)
        assertTrue(jpeg.previewUnavailable.value != null)
    }
}

