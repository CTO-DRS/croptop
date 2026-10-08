package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeText

@Composable
fun DefinePatternDialog(onDefine: (String) -> String?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun define() {
        if (name.isBlank()) {
            error = "Enter a name for this pattern"
        } else {
            val failureMessage = onDefine(name.trim())
            if (failureMessage == null) {
                onDismiss()
            } else {
                error = failureMessage
            }
        }
    }
    AeDialog(
        title = "Define Pattern",
        body = "Save visible pixels in the current selection as a reusable pattern. With no selection, the whole canvas is used.",
        onDismiss = onDismiss,
        onConfirm = ::define,
        width = 420.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("Define", ::define, style = AeButtonStyle.Primary)
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                error = null
            },
            label = { AeText("Pattern name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { AeText(it, color = app.croptop.core.designsystem.theme.Ae.colors.danger) }
    }
}
