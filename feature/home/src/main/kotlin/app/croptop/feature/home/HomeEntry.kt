package app.croptop.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.croptop.core.model.NewDocumentSpec

/** Home wired to its ViewModel (recents). [HomeScreen] stays stateless for previews and screenshot tests. */
@Composable
fun HomeEntry(
    onNewDocument: (NewDocumentSpec) -> Unit,
    onOpen: () -> Unit,
    onOpenUri: (String) -> Unit,
    onOpenUriInNewWindow: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPaste: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recents by viewModel.recent.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val defaultSpace by viewModel.defaultSpace.collectAsStateWithLifecycle()
    val actionFeedback by viewModel.actionFeedback.collectAsStateWithLifecycle()
    HomeScreen(
        onNewDocument = onNewDocument,
        onOpen = onOpen,
        onOpenSettings = onOpenSettings,
        onOpenLibrary = onOpenLibrary,
        recents = recents,
        onOpenRecent = onOpenUri,
        onOpenRecentInNewWindow = onOpenUriInNewWindow,
        onRemoveRecent = { viewModel.remove(it) },
        onTogglePin = { viewModel.togglePin(it) },
        recentActionFeedback = actionFeedback,
        onPaste = onPaste,
        savedPresets = presets,
        onSavePreset = { viewModel.savePreset(it) },
        defaultSpace = defaultSpace,
    )
}
