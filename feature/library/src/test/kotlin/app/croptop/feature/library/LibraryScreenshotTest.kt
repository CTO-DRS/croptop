package app.croptop.feature.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.paging.PagingData
import app.croptop.core.data.catalog.CatalogRepository
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.CatalogImage
import app.croptop.core.model.ColorLabel
import app.croptop.core.model.Flag
import app.croptop.core.model.LibraryCollection
import app.croptop.core.model.LibraryFilter
import app.croptop.core.model.LibraryFolder
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

class FakeCatalog(folders: List<LibraryFolder>, images: List<CatalogImage>) : CatalogRepository {
    val store = MutableStateFlow(images)
    override val folders: Flow<List<LibraryFolder>> = MutableStateFlow(folders)

    override fun images(filter: LibraryFilter) = kotlinx.coroutines.flow.combine(store, collectionStore) { list, _ -> list }.map { list ->
        list.filter {
            it.rating >= filter.minRating &&
                (filter.flag == null || it.flag == filter.flag) &&
                (filter.collectionId == null || it.id in members[filter.collectionId].orEmpty())
        }
    }

    override fun pagedImages(filter: LibraryFilter) = images(filter).map { PagingData.from(it) }

    override fun ids(filter: LibraryFilter) = images(filter).map { list -> list.map { it.id } }

    override fun images(ids: List<Long>) = store.map { list -> ids.mapNotNull { id -> list.firstOrNull { it.id == id } } }

    override suspend fun uris(ids: List<Long>) = ids.mapNotNull { id -> store.value.firstOrNull { it.id == id }?.uri }

    override fun keywords(imageId: Long): Flow<List<String>> = flowOf(listOf("sunset", "coast"))

    override fun image(id: Long) = store.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun addFolder(treeUri: String, name: String, now: Long) = 0

    override suspend fun removeFolder(treeUri: String) = Unit

    override suspend fun backfillMetadata(batch: Int) = 0

    override suspend fun setRating(ids: List<Long>, rating: Int) {
        store.value = store.value.map { if (it.id in ids) it.copy(rating = rating) else it }
    }

    override suspend fun setFlag(ids: List<Long>, flag: Flag) {
        store.value = store.value.map { if (it.id in ids) it.copy(flag = flag) else it }
    }

    override suspend fun setLabel(ids: List<Long>, label: ColorLabel) = Unit

    override suspend fun setIptc(ids: List<Long>, edit: app.croptop.core.model.IptcEdit) {
        store.value = store.value.map {
            if (it.id !in ids) it else it.copy(title = edit.title ?: it.title, creator = edit.creator ?: it.creator)
        }
    }

    override suspend fun addKeywords(ids: List<Long>, keywords: List<String>) = Unit

    val collectionStore = MutableStateFlow(listOf<LibraryCollection>())
    val members = mutableMapOf<Long, MutableSet<Long>>()
    override val collections: Flow<List<LibraryCollection>> = collectionStore

    override suspend fun createCollection(name: String, smart: LibraryFilter?, now: Long): Long {
        val id = (collectionStore.value.maxOfOrNull { it.id } ?: 0) + 1
        collectionStore.value = collectionStore.value + LibraryCollection(id, name, smart?.criteria(), if (smart == null) 0 else null)
        return id
    }

    override suspend fun renameCollection(id: Long, name: String) {
        collectionStore.value = collectionStore.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun deleteCollection(id: Long) {
        collectionStore.value = collectionStore.value.filter { it.id != id }
    }

    override suspend fun addToCollection(collectionId: Long, ids: List<Long>, now: Long) {
        members.getOrPut(collectionId) { mutableSetOf() } += ids
        collectionStore.value =
            collectionStore.value.map { if (it.id == collectionId) it.copy(imageCount = members.getValue(collectionId).size) else it }
    }

    override suspend fun removeFromCollection(collectionId: Long, ids: List<Long>) {
        members[collectionId]?.removeAll(ids.toSet())
        collectionStore.value =
            collectionStore.value.map { if (it.id == collectionId) it.copy(imageCount = members[collectionId]?.size ?: 0) else it }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class LibraryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val photos = (1..14).map { i ->
        CatalogImage(
            id = i.toLong(), uri = "content://t/$i", folderUri = "content://t", name = if (i % 3 == 0) "DSCF$i.RAF" else "IMG_20$i.jpg",
            mimeType = "image/jpeg", sizeBytes = 12_000_000, width = 6240, height = 4160, takenAt = 1_758_000_000_000 - i * 60_000L,
            camera = "Fujifilm X-T5", lens = "XF33mmF1.4 R LM WR", iso = 400, exposure = "1/250 s", aperture = 2.8f, focalLength = 33f,
            rating = i % 6,
            flag = if (i % 5 == 0) {
                Flag.REJECT
            } else if (i % 4 == 0) {
                Flag.PICK
            } else {
                Flag.NONE
            },
            label = if (i % 7 == 0) ColorLabel.GREEN else ColorLabel.NONE,
        )
    }

    @Test
    fun libraryGrid() {
        val vm =
            LibraryViewModel(
                FakeCatalog(listOf(LibraryFolder("content://t", "Japan 2026", 14), LibraryFolder("content://u", "Portraits", 212)), photos),
            )
        vm.click(4, additive = false, range = false)
        kotlinx.coroutines.runBlocking {
            vm.createCollection("Best of Kyoto").join()
            vm.createSmartCollection("Five stars").join()
            vm.showAll()
        }
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) { LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = vm, batchVm = testBatchVm()) }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/library_1280x800.png")
    }

    @Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
    @Test
    fun filterBarAtNarrowWidthAndTwoHundredPercentFontScale() {
        val vm = LibraryViewModel(
            FakeCatalog(listOf(LibraryFolder("content://t", "Japan 2026", 14)), photos),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                    CropTopTheme(density = AeDensity.Touch) {
                        LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = vm, batchVm = testBatchVm())
                    }
                }
            }
        }
        val sort = compose.onNodeWithText("Newest first")
        val sortNode = sort.fetchSemanticsNode()
        assertTrue(
            generateSequence(sortNode.parent) { it.parent }.any { it.config.getOrNull(SemanticsActions.ScrollBy) != null },
            "The 200% narrow filter bar must expose vertical scrolling for controls below the viewport",
        )
        compose.onRoot().captureRoboImage("src/test/snapshots/library_narrow_font200.png")
    }

    @Test
    fun colorLabelFiltersExposeTheirCheckedStateToAccessibility() {
        val vm = LibraryViewModel(
            FakeCatalog(listOf(LibraryFolder("content://t", "Japan 2026", 14)), photos),
        )
        vm.setFilter { it.copy(label = ColorLabel.GREEN) }
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = vm, batchVm = testBatchVm())
                }
            }
        }

        compose.onNodeWithContentDescription("Filter Green label").assertIsOn()
        compose.onNodeWithContentDescription("Filter Red label").assertIsOff()
    }

    @Test
    fun cullingAutoAdvances() {
        val catalog = FakeCatalog(emptyList(), photos)
        val vm = LibraryViewModel(catalog)
        vm.click(1, additive = false, range = false)
        vm.rate(5)
        assertEquals(5, catalog.store.value.first { it.id == 1L }.rating)
        assertEquals(2L, vm.focus.value) // auto-advanced
        vm.autoAdvance.value = false
        vm.flag(Flag.PICK)
        assertEquals(2L, vm.focus.value)
        assertEquals(Flag.PICK, catalog.store.value.first { it.id == 2L }.flag)
    }

    @Test
    fun emptyLibraryInvitesToAddFolder() {
        val vm = LibraryViewModel(FakeCatalog(emptyList(), emptyList()))
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) { LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = vm, batchVm = testBatchVm()) }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/library_empty.png")
    }

    private fun viewShot(key: androidx.compose.ui.input.key.Key, name: String, select: List<Long>) {
        val vm = LibraryViewModel(FakeCatalog(listOf(LibraryFolder("content://t", "Japan 2026", 14)), photos))
        select.forEachIndexed { i, id -> vm.click(id, additive = i > 0, range = false) }
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) { LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = vm, batchVm = testBatchVm()) }
            }
        }
        compose.waitForIdle()
        @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/library_$name.png")
    }

    @Test
    fun compareView() {
        viewShot(androidx.compose.ui.input.key.Key.C, "compare_view", listOf(3L))
    }

    @Test
    fun surveyView() {
        viewShot(androidx.compose.ui.input.key.Key.N, "survey_view", listOf(2L, 3L, 4L, 6L))
    }

    @Test
    fun compareMarksActiveFrameWithoutAdvancing() {
        val catalog = FakeCatalog(emptyList(), photos)
        val vm = LibraryViewModel(catalog)
        vm.click(1, additive = false, range = false)
        vm.pinnedTarget.value = 5L
        vm.rate(4)
        assertEquals(4, catalog.store.value.first { it.id == 5L }.rating)
        assertEquals(1L, vm.focus.value)
    }

    @Test
    fun arrowKeysScrollTheFocusIntoView() {
        val many = (1..400).map { photos[0].copy(id = it.toLong(), uri = "content://t/$it", name = "P$it.jpg") }
        val vm = LibraryViewModel(FakeCatalog(listOf(LibraryFolder("content://t", "All", 400)), many))
        vm.click(1, additive = false, range = false)
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) { LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = vm, batchVm = testBatchVm()) }
            }
        }
        compose.waitForIdle()
        @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
        compose.onRoot().performKeyInput { repeat(40) { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) } }
        compose.waitForIdle()
        val focus = vm.focus.value!!
        assert(focus > 100) { "focus only reached $focus" }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("P$focus.jpg", substring = true)).assertIsDisplayed()
        @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
        compose.onRoot().performKeyInput { repeat(40) { pressKey(androidx.compose.ui.input.key.Key.DirectionUp) } }
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("P1.jpg,", substring = true)).assertIsDisplayed()
    }

    @Test
    fun collectionsFilterAndCollect() {
        val catalog = FakeCatalog(emptyList(), photos)
        val vm = LibraryViewModel(catalog)
        vm.click(2, additive = false, range = false)
        vm.click(3, additive = true, range = false)
        kotlinx.coroutines.runBlocking { vm.createCollection("Trip").join() }
        val trip = catalog.collectionStore.value.single()
        assertEquals(2, trip.imageCount)
        vm.showCollection(trip)
        assertEquals(listOf(2L, 3L), vm.ids.value)
        vm.click(3, additive = false, range = false)
        kotlinx.coroutines.runBlocking { vm.removeSelectionFromCollection().join() }
        assertEquals(listOf(2L), vm.ids.value)
        vm.setFilter { it.copy(minRating = 4) }
        kotlinx.coroutines.runBlocking { vm.createSmartCollection("Four up").join() }
        val smart = catalog.collectionStore.value.first { it.isSmart }
        assertEquals(LibraryFilter(minRating = 4), smart.smart)
        vm.showAll()
        vm.showCollection(smart)
        assertEquals(smart.id, vm.activeSmart.value)
        assertEquals(4, vm.filter.value.minRating)
        kotlinx.coroutines.runBlocking { vm.deleteCollection(smart.id).join() }
        assertEquals(null, vm.activeSmart.value)
        assertEquals(LibraryFilter(), vm.filter.value.copy(minRating = 0))
    }
}

private fun testBatchVm() = app.croptop.feature.library.batch.BatchExportViewModel(
    app.croptop.feature.library.batch.BatchExportController(
        app.croptop.core.engine.fake.FakeEngine(),
        object : app.croptop.feature.library.batch.BatchOutput {
            override suspend fun create(folder: String, baseName: String, format: app.croptop.core.engine.api.ExportFormat) = null
        },
    ),
    FakeCatalog(emptyList(), emptyList()),
    object : app.croptop.core.data.DevelopRepository {
        override fun settings(uri: String) = kotlinx.coroutines.flow.flowOf(app.croptop.core.model.DevelopSettings())
        override suspend fun save(uri: String, settings: app.croptop.core.model.DevelopSettings, now: Long) = Unit
    },
    app.croptop.feature.library.batch.ExportPresetStore(object : app.croptop.feature.library.batch.PresetStorage {
        override fun read(): String? = null
        override fun write(json: String) = Unit
    }),
)
