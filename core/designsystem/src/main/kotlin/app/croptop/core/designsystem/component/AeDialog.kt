package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.croptop.core.designsystem.theme.Ae

/**
 * Modal dialog: title, optional body text, content slot, right-aligned actions.
 * Esc dismisses; Enter triggers [onConfirm] when provided. One primary action per dialog.
 */
@Composable
fun AeDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    width: Dp = 440.dp,
    onConfirm: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = Ae.colors
        // Take keyboard focus on open so Enter/Esc work immediately (no stray focus on the first control).
        val focus = remember { FocusRequester() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            modifier
                .widthIn(max = width)
                .fillMaxWidth()
                .background(c.surface2, Ae.shapes.popover)
                .border(1.dp, c.lineStrong, Ae.shapes.popover)
                .semantics { paneTitle = title }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }

                        Key.Enter, Key.NumPadEnter -> onConfirm?.let {
                            // Commit the focused field first (fields commit on focus loss), then confirm.
                            focusManager.clearFocus(force = true)
                            it()
                            runCatching { focus.requestFocus() } // keep Esc working if the dialog stays open
                            true
                        } ?: false

                        else -> false
                    }
                }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AeText(title, style = Ae.type.title, maxLines = 2)
            if (body != null) AeText(body, color = c.textSecondary, maxLines = 6)
            content()
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}
