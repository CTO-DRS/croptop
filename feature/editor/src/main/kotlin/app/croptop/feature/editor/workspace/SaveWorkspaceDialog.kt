package app.croptop.feature.editor.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.theme.Ae

/** Name the current panel arrangement. Saved workspaces appear in the Window menu. */
@Composable
fun SaveWorkspaceDialog(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = name.isNotBlank()
    fun save() = if (valid) onSave(name.trim()) else Unit
    val c = Ae.colors
    AeDialog(
        title = "Save Workspace",
        body = "Saves which panels are open, their order and the panel width.",
        onDismiss = onCancel,
        onConfirm = ::save,
        width = 400.dp,
        actions = {
            AeButton("Cancel", onCancel)
            AeButton("Save", ::save, style = AeButtonStyle.Primary, enabled = valid)
        },
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            singleLine = true,
            textStyle = Ae.type.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(Ae.density.controlHeight)
                .background(c.surface3, Ae.shapes.control)
                .border(1.dp, c.accent, Ae.shapes.control)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics { contentDescription = "Workspace name" }
                .focusRequester(focus),
        )
    }
}
