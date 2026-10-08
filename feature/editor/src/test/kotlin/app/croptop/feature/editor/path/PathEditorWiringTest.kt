package app.croptop.feature.editor.path

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.WorkspaceLayout
import app.croptop.feature.editor.EditorContent
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.files.ShareCache
import app.croptop.feature.editor.session.EditorViewModel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class PathEditorWiringTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pathsPanelAndShapeOptionsAreReachableFromTheEditor() {
        val route = EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100)))
        val engine = FakeEngine()
        val viewModel = EditorViewModel(
            engine,
            object : RecentDocumentsRepository {
                override fun observe(limit: Int) = flowOf(emptyList<RecentDocument>())
                override suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long) = Unit
                override suspend fun remove(uri: String) = Unit
                override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
            },
            object : ShareCache {
                override fun newFile(name: String) = File.createTempFile("path-ui", name)
                override fun uriFor(file: File) = file.toURI().toString()
            },
            OpenDocuments(engine),
            { null },
            route,
        )
        viewModel.newLayer()
        val layout = mutableStateOf(WorkspaceLayout.Essentials)

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme {
                    EditorContent(
                        viewModel,
                        route,
                        onClose = {},
                        layout = layout.value,
                        onLayout = { next ->
                            layout.value = next
                            CompletableDeferred(Unit)
                        },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Rectangle").performClick()
        compose.onNodeWithContentDescription("Rectangle").assertExists()

        compose.onNodeWithText("Window").performClick()
        compose.onNodeWithText("Paths").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Paths").assertExists()
        compose.onNodeWithText("No paths on this shape layer. Draw with Pen or a shape tool.").assertExists()
    }
}
