package app.croptop.feature.editor.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.engine.api.LayerThumb
import app.croptop.core.engine.api.PatternInfo

@Composable
fun PatternLibraryDialog(
    patterns: List<PatternInfo>,
    patternThumbnail: (String, Int) -> LayerThumb?,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<PatternInfo?>(null) }
    AeDialog(
        title = "Pattern Library",
        body = "Patterns are shared across documents. Documents keep embedded copies of patterns they use.",
        onDismiss = onDismiss,
        onConfirm = onDismiss,
        width = 560.dp,
        actions = { AeButton("Done", onDismiss) },
    ) {
        PatternPicker(
            patterns = patterns,
            selectedId = null,
            onSelect = {},
            patternThumbnail = patternThumbnail,
            onDelete = { id -> pendingDelete = patterns.firstOrNull { it.id == id } },
        )
    }

    pendingDelete?.let { pattern ->
        AeDialog(
            title = "Delete ${pattern.name}?",
            body = "This removes the pattern from the shared library. Fill layers and effects that already use it keep their embedded copy.",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDelete(pattern.id)
                pendingDelete = null
            },
            width = 420.dp,
            actions = {
                AeButton("Cancel", { pendingDelete = null })
                AeButton(
                    "Delete Pattern",
                    {
                        onDelete(pattern.id)
                        pendingDelete = null
                    },
                    style = AeButtonStyle.Danger,
                )
            },
        )
    }
}
