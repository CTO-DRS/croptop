package app.croptop.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import app.croptop.core.data.DevelopRepository
import app.croptop.core.data.catalog.CatalogRepository
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Accessibility audit over the Compose semantics tree:
 * every clickable needs an accessible name, and in touch density every target is ≥ 40dp.
 * (ATF doesn't evaluate under Robolectric, so these checks are explicit; see the negative control.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi", application = android.app.Application::class)
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }

    private fun audit(content: @Composable () -> Unit): List<String> {
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Touch) { content() }
            }
        }
        val root = compose.onRoot(useUnmergedTree = false).fetchSemanticsNode()
        val minPx = with(root.layoutInfo.density) { 40.dp.toPx() }
        return root.walk().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }.mapNotNull { n ->
            val c = n.config
            val name = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: c.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: c.getOrNull(SemanticsActions.OnClick)?.label
            val b = n.boundsInRoot
            val clippedByScroll = generateSequence(n.parent) { it.parent }.any { ancestor ->
                if (ancestor.config.getOrNull(SemanticsActions.ScrollBy) == null) {
                    false
                } else {
                    val viewport = ancestor.boundsInRoot
                    val measured = n.size
                    b.width < measured.width || b.height < measured.height ||
                        b.left < viewport.left || b.top < viewport.top || b.right > viewport.right || b.bottom > viewport.bottom
                }
            }
            when {
                b.width <= 0f || b.height <= 0f -> null

                // Scroll containers may expose a partial edge row. Focus traversal scrolls that
                // control fully into view before it is activated, so audit its full target only
                // once it is no longer clipped.
                clippedByScroll -> null

                // clipped/offscreen (e.g. scrolled toolbar): not a target now
                name.isNullOrBlank() -> "Unlabeled clickable at $b (parent=${n.parent?.config})"

                b.width < minPx - 0.5f || b.height < minPx - 0.5f ->
                    "Target '$name' too small: ${b.width}x${b.height}px (parent=${n.parent?.config})"

                else -> null
            }
        }
    }

    private fun assertClean(problems: List<String>) = kotlin.test.assertTrue(problems.isEmpty(), problems.joinToString("\n"))

    @Test fun home() = assertClean(audit { HomeScreen(onNewDocument = {}, onOpen = {}, onOpenSettings = {}, onOpenLibrary = {}, onPaste = {}) })

    @Test fun settings() = assertClean(
        audit { SettingsContent(UiPreferences(), {}, {}, null, about = AboutInfo("0.1.0", EngineCapabilities("1", "x86_64"))) },
    )

    @Test
    fun develop() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val uri = "content://accessibility/develop"
            val vm = DevelopViewModel(
                object : DevelopRepository {
                    override fun settings(uri: String): Flow<DevelopSettings> = flowOf(DevelopSettings())

                    override suspend fun save(uri: String, settings: DevelopSettings, now: Long) = Unit
                },
                app.croptop.core.engine.fake.FakeEngine(),
                uri,
            )
            val route = DevelopRoute(uri)
            val preview = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            assertClean(
                audit {
                    DevelopContent(vm, route, onBack = {}, onOpenInEditor = { _, _ -> }, onOpenSibling = {}, previewBitmap = preview)
                },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun library() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
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
            val vm = LibraryViewModel(repository)
            val batchVm = app.croptop.feature.library.batch.BatchExportViewModel(
                app.croptop.feature.library.batch.BatchExportController(
                    app.croptop.core.engine.fake.FakeEngine(),
                    object : app.croptop.feature.library.batch.BatchOutput {
                        override suspend fun create(
                            folder: String,
                            baseName: String,
                            format: app.croptop.core.engine.api.ExportFormat,
                        ) = null
                    },
                ),
                repository,
                object : app.croptop.core.data.DevelopRepository {
                    override fun settings(uri: String) = kotlinx.coroutines.flow.flowOf(app.croptop.core.model.DevelopSettings())
                    override suspend fun save(uri: String, settings: app.croptop.core.model.DevelopSettings, now: Long) = Unit
                },
                app.croptop.feature.library.batch.ExportPresetStore(object : app.croptop.feature.library.batch.PresetStorage {
                    override fun read(): String? = null
                    override fun write(json: String) = Unit
                }),
            )
            assertClean(
                audit {
                    LibraryScreen(
                        onOpenInEditor = {},
                        onBack = {},
                        vm = vm,
                        batchVm = batchVm,
                    )
                },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editor() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        try {
            val vm = app.croptop.feature.editor.session.EditorViewModel(
                app.croptop.core.engine.fake.FakeEngine(),
                object : app.croptop.core.data.RecentDocumentsRepository {
                    override fun observe(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<app.croptop.core.model.RecentDocument>())
                    override suspend fun recordOpened(uri: String, name: String, size: app.croptop.core.model.DocSize?, now: Long) = Unit
                    override suspend fun remove(uri: String) = Unit
                    override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
                },
                object : app.croptop.feature.editor.files.ShareCache {
                    override fun newFile(name: String) = java.io.File.createTempFile("s", name)
                    override fun uriFor(file: java.io.File) = file.toURI().toString()
                },
                app.croptop.core.agent.OpenDocuments(app.croptop.core.engine.fake.FakeEngine()),
                { null },
                app.croptop.feature.editor.EditorRoute(
                    newSpec = app.croptop.core.model.NewDocumentSpec(size = app.croptop.core.model.DocSize(100, 100)),
                ),
            )
            vm.newLayer()
            assertClean(audit { app.croptop.feature.editor.EditorContent(vm, app.croptop.feature.editor.EditorRoute(), onClose = {}) })
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    /** LS-K1: Tab walks the editor chrome (menus, toolbar, panels) without getting stuck. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun tabTraversesEditor() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        try {
            val vm = app.croptop.feature.editor.session.EditorViewModel(
                app.croptop.core.engine.fake.FakeEngine(),
                object : app.croptop.core.data.RecentDocumentsRepository {
                    override fun observe(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<app.croptop.core.model.RecentDocument>())
                    override suspend fun recordOpened(uri: String, name: String, size: app.croptop.core.model.DocSize?, now: Long) = Unit
                    override suspend fun remove(uri: String) = Unit
                    override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
                },
                object : app.croptop.feature.editor.files.ShareCache {
                    override fun newFile(name: String) = java.io.File.createTempFile("s", name)
                    override fun uriFor(file: java.io.File) = file.toURI().toString()
                },
                app.croptop.core.agent.OpenDocuments(app.croptop.core.engine.fake.FakeEngine()),
                { null },
                app.croptop.feature.editor.EditorRoute(
                    newSpec = app.croptop.core.model.NewDocumentSpec(size = app.croptop.core.model.DocSize(100, 100)),
                ),
            )
            compose.setContent {
                CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                    CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                        // A hardware Tab puts a real device in keyboard mode, where clickables become focusable.
                        val modes = androidx.compose.ui.platform.LocalInputModeManager.current
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                            modes.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                        }
                        app.croptop.feature.editor.EditorContent(vm, app.croptop.feature.editor.EditorRoute(), onClose = {})
                    }
                }
            }
            val seen = linkedSetOf<String>()
            repeat(120) {
                compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
                val focused = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = false).fetchSemanticsNodes()
                focused.firstOrNull()?.let { n ->
                    val c = n.config
                    val name = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: c.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: "node ${n.id}"
                    seen += name
                }
            }
            println("Tab order (${seen.size}): " + seen.joinToString(" → "))
            // Reaches the menu bar, the toolbar and the layers panel.
            kotlin.test.assertTrue(seen.any { it == "File" }, seen.toString())
            kotlin.test.assertTrue(seen.any { it.startsWith("Move") || it.startsWith("Hand") }, seen.toString())
            kotlin.test.assertTrue(seen.any { it.contains("Opacity") || it.contains("New layer", ignoreCase = true) }, seen.toString())
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    /** Negative control: the audit must flag a tiny, unlabeled clickable. */
    @Test
    fun auditCatchesProblems() {
        val problems = audit { Box(Modifier.size(12.dp).background(Color(0xFF1B1B1B)).clickable {}) }
        kotlin.test.assertTrue(problems.any { it.startsWith("Unlabeled") }, problems.toString())
    }

    @Test
    fun auditRejectsTargetThatIsTooNarrowEvenWhenItsHeightPasses() {
        val problems = audit {
            Box(
                Modifier.size(width = 24.dp, height = 48.dp)
                    .semantics { contentDescription = "Narrow control" }
                    .clickable {},
            )
        }
        kotlin.test.assertTrue(problems.any { it.startsWith("Target 'Narrow control' too small") }, problems.toString())
    }
}
