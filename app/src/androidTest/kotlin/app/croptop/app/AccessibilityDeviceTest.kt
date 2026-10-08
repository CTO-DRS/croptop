package app.croptop.app

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.data.DevelopRepository
import app.croptop.core.data.catalog.CatalogRepository
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.CatalogImage
import app.croptop.core.model.ColorLabel
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.Flag
import app.croptop.core.model.IptcEdit
import app.croptop.core.model.LibraryCollection
import app.croptop.core.model.LibraryFilter
import app.croptop.core.model.LibraryFolder
import app.croptop.core.model.UiPreferences
import app.croptop.feature.develop.DevelopContent
import app.croptop.feature.develop.DevelopRoute
import app.croptop.feature.develop.DevelopViewModel
import app.croptop.feature.home.HomeScreen
import app.croptop.feature.library.LibraryScreen
import app.croptop.feature.library.LibraryViewModel
import app.croptop.feature.settings.AboutInfo
import app.croptop.feature.settings.SettingsContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side accessible names and minimum touch target audit for app entry/settings screens. */
@RunWith(AndroidJUnit4::class)
class AccessibilityDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }

    private fun audit(content: @androidx.compose.runtime.Composable () -> Unit): List<String> {
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) { content() }
            }
        }
        val root = compose.onRoot(useUnmergedTree = false).fetchSemanticsNode()
        val minimum = with(root.layoutInfo.density) { 40f * density }
        return root.walk().mapNotNull { node ->
            val config = node.config
            if (config.getOrNull(SemanticsActions.OnClick) == null) return@mapNotNull null
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
            val clippedByScroll = generateSequence(node.parent) { it.parent }.any { ancestor ->
                if (ancestor.config.getOrNull(SemanticsActions.ScrollBy) == null) {
                    false
                } else {
                    val viewport = ancestor.boundsInRoot
                    val measured = node.size
                    bounds.width < measured.width || bounds.height < measured.height ||
                        bounds.left < viewport.left || bounds.top < viewport.top ||
                        bounds.right > viewport.right || bounds.bottom > viewport.bottom
                }
            }
            if (clippedByScroll) return@mapNotNull null
            val label = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: config.getOrNull(SemanticsActions.OnClick)?.label
            when {
                label.isNullOrBlank() -> "Unlabeled clickable at $bounds"

                bounds.width < minimum - 0.5f || bounds.height < minimum - 0.5f ->
                    "Target '$label' too small: ${bounds.width}x${bounds.height}px"

                else -> null
            }
        }
    }

    private fun libraryViewModel(): LibraryViewModel {
        val image = CatalogImage(
            id = 1,
            uri = "content://accessibility/library/1",
            folderUri = "content://accessibility/library",
            name = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024,
            width = 64,
            height = 64,
        )
        val repository = object : CatalogRepository {
            override val folders: Flow<List<LibraryFolder>> = flowOf(listOf(LibraryFolder(image.folderUri, "Photos", 1)))
            override val collections: Flow<List<LibraryCollection>> = flowOf(emptyList())
            override fun images(filter: LibraryFilter): Flow<List<CatalogImage>> = flowOf(listOf(image))
            override fun pagedImages(filter: LibraryFilter): Flow<PagingData<CatalogImage>> = flowOf(PagingData.from(listOf(image)))
            override fun ids(filter: LibraryFilter): Flow<List<Long>> = flowOf(listOf(image.id))
            override fun images(ids: List<Long>): Flow<List<CatalogImage>> = flowOf(listOf(image).filter { it.id in ids })
            override suspend fun uris(ids: List<Long>): List<String> = listOf(image.uri).filter { image.id in ids }
            override fun keywords(imageId: Long): Flow<List<String>> = flowOf(emptyList())
            override fun image(id: Long): Flow<CatalogImage?> = flowOf(image.takeIf { it.id == id })
            override suspend fun addFolder(treeUri: String, name: String, now: Long): Int = 1
            override suspend fun removeFolder(treeUri: String) = Unit
            override suspend fun backfillMetadata(batch: Int): Int = 0
            override suspend fun setRating(ids: List<Long>, rating: Int) = Unit
            override suspend fun setFlag(ids: List<Long>, flag: Flag) = Unit
            override suspend fun setLabel(ids: List<Long>, label: ColorLabel) = Unit
            override suspend fun addKeywords(ids: List<Long>, keywords: List<String>) = Unit
            override suspend fun setIptc(ids: List<Long>, edit: IptcEdit) = Unit
            override suspend fun createCollection(name: String, smart: LibraryFilter?, now: Long): Long = 1
            override suspend fun renameCollection(id: Long, name: String) = Unit
            override suspend fun deleteCollection(id: Long) = Unit
            override suspend fun addToCollection(collectionId: Long, ids: List<Long>, now: Long) = Unit
            override suspend fun removeFromCollection(collectionId: Long, ids: List<Long>) = Unit
        }
        catalogRepository = repository
        return LibraryViewModel(repository)
    }

    private lateinit var catalogRepository: CatalogRepository

    /** The Library's batch-export view model without Hilt: a native engine, the catalog fake, a neutral Develop store and no output folder. */
    private fun batchViewModel(): app.croptop.feature.library.batch.BatchExportViewModel = app.croptop.feature.library.batch.BatchExportViewModel(
        app.croptop.feature.library.batch.BatchExportController(
            NativeEngine(),
            object : app.croptop.feature.library.batch.BatchOutput {
                override suspend fun create(folder: String, baseName: String, format: app.croptop.core.engine.api.ExportFormat) = null
            },
        ),
        catalogRepository,
        object : DevelopRepository {
            override fun settings(uri: String): Flow<DevelopSettings> = flowOf(DevelopSettings())

            override suspend fun save(uri: String, settings: DevelopSettings, now: Long) = Unit
        },
        app.croptop.feature.library.batch.ExportPresetStore(object : app.croptop.feature.library.batch.PresetStorage {
            override fun read(): String? = null

            override fun write(json: String) = Unit
        }),
    )

    private fun developViewModel(uri: String): DevelopViewModel {
        val repository = object : DevelopRepository {
            override fun settings(uri: String): Flow<DevelopSettings> = flowOf(DevelopSettings())

            override suspend fun save(uri: String, settings: DevelopSettings, now: Long) = Unit
        }
        return DevelopViewModel(repository, NativeEngine(), uri)
    }

    @Test
    fun homeControlsHaveNamesAndTouchTargets() {
        val problems = audit {
            HomeScreen(
                onNewDocument = {},
                onOpen = {},
                onOpenSettings = {},
                onOpenLibrary = {},
                onPaste = {},
            )
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun homeTabTraversalReachesPrimaryActionsAndDestinations() {
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    HomeScreen(
                        onNewDocument = {},
                        onOpen = {},
                        onOpenSettings = {},
                        onOpenLibrary = {},
                        onPaste = {},
                    )
                }
            }
        }

        val focusedLabels = linkedSetOf<String>()
        repeat(120) {
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                label?.let(focusedLabels::add)
            }
        }
        listOf("Open image…", "New canvas…", "Library", "Settings").forEach { control ->
            assertTrue("Tab traversal skipped '$control': $focusedLabels", focusedLabels.any { control in it })
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun newDocumentDialogTabTraversalReachesFieldsAndActions() {
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    HomeScreen(
                        onNewDocument = {},
                        onOpen = {},
                        onOpenSettings = {},
                        onOpenLibrary = {},
                    )
                }
            }
        }

        compose.onNodeWithText("New canvas…", substring = true).performClick()
        val dialogKeyTarget = compose.onNodeWithText("Cancel")
        dialogKeyTarget.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val focusedLabels = linkedSetOf<String>()
        repeat(120) {
            dialogKeyTarget.performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                label?.let(focusedLabels::add)
            }
        }
        listOf("Document name", "Width", "Height", "Resolution", "Cancel", "Create").forEach { control ->
            assertTrue("New Document dialog Tab traversal skipped '$control': $focusedLabels", focusedLabels.any { control in it })
        }
    }

    @Test
    fun settingsControlsHaveNamesAndTouchTargets() {
        val problems = audit {
            SettingsContent(
                prefs = UiPreferences(),
                onUpdate = {},
                onBack = {},
                onOpenCatalog = null,
                about = AboutInfo("0.1.0", EngineCapabilities("native-1", "x86_64")),
            )
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun developControlsHaveNamesAndTouchTargets() {
        val uri = "content://accessibility/develop"
        val viewModel = developViewModel(uri)
        val preview = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val problems = audit {
            DevelopContent(
                viewModel,
                DevelopRoute(uri),
                onBack = {},
                onOpenInEditor = { _, _ -> },
                onOpenSibling = {},
                previewBitmap = preview,
            )
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun developTabTraversalReachesBasicAdjustmentControls() {
        val uri = "content://accessibility/develop"
        val viewModel = developViewModel(uri)
        val preview = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    DevelopContent(
                        viewModel,
                        DevelopRoute(uri),
                        onBack = {},
                        onOpenInEditor = { _, _ -> },
                        onOpenSibling = {},
                        previewBitmap = preview,
                    )
                }
            }
        }

        val focusedLabels = linkedSetOf<String>()
        repeat(160) {
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                label?.let(focusedLabels::add)
            }
        }
        listOf("Exposure", "Contrast", "Highlights", "Reset", "Open in Editor").forEach { control ->
            assertTrue("Tab traversal skipped '$control': $focusedLabels", control in focusedLabels)
        }
    }

    @Test
    fun libraryControlsHaveNamesAndTouchTargets() {
        val viewModel = libraryViewModel()
        val problems = audit { LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = viewModel, batchVm = batchViewModel()) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        compose.onNodeWithContentDescription("Filter Red label").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Filter Red label").performClick().assertIsOff()
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun libraryTabTraversalReachesFilterControls() {
        val viewModel = libraryViewModel()
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    LibraryScreen(onOpenInEditor = {}, onBack = {}, vm = viewModel, batchVm = batchViewModel())
                }
            }
        }

        val focusedLabels = linkedSetOf<String>()
        repeat(160) {
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                label?.let(focusedLabels::add)
            }
        }
        listOf("Search photos", "Any rating", "All flags", "Filter Red label", "Newest first").forEach { control ->
            assertTrue("Tab traversal skipped '$control': $focusedLabels", control in focusedLabels)
        }
    }
}
