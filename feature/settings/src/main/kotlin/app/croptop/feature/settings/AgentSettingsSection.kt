package app.croptop.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.croptop.core.agent.AgentStatus
import app.croptop.core.data.AgentSettings
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeSwitch
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae

/** Everything the "AI agents (MCP)" section needs. */
data class AgentUi(
    val settings: AgentSettings,
    val status: AgentStatus,
    val clientConfig: String,
    val onEnabled: (Boolean) -> Unit,
    val onRegenerate: () -> Unit,
    val onConfirmExport: (Boolean) -> Unit = {},
)

@Composable
fun AgentSettingsBody(ui: AgentUi) {
    val c = Ae.colors
    val context = LocalContext.current
    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                AeText("Allow AI agents to edit")
                AeText(
                    "Lets an MCP client on this device (e.g. Claude, Gemini CLI) list layers and make undoable edits in the document you have open.",
                    style = Ae.type.caption,
                    color = c.textTertiary,
                    maxLines = 3,
                )
            }
            AeSwitch(
                checked = ui.settings.enabled,
                onCheckedChange = { on -> ui.onEnabled(on) },
                contentDescription = "Allow AI agents to edit",
            )
        }
        if (ui.settings.enabled) {
            val s = ui.status
            AeText(
                when {
                    s.error != null -> s.error!!
                    !s.running -> "Starting…"
                    s.client != null -> "Connected: ${s.client}" + (s.lastTool?.let { " · last action: $it" } ?: "")
                    else -> "Waiting for an agent on http://127.0.0.1:${s.port}/mcp"
                },
                style = Ae.type.label,
                color = if (s.error != null) {
                    c.warning
                } else if (s.client != null) {
                    c.success
                } else {
                    c.textSecondary
                },
                maxLines = 2,
            )
            AeText(
                ui.clientConfig,
                style = Ae.type.mono,
                color = c.textSecondary,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth().background(
                    c.surface2,
                    Ae.shapes.control,
                ).border(1.dp, c.lineHairline, Ae.shapes.control).padding(12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeButton("Copy client config", { copy("CropTop MCP config", ui.clientConfig) }, style = AeButtonStyle.Primary)
                AeButton("Copy token", { copy("CropTop MCP token", ui.settings.token) })
                AeButton("New token", ui.onRegenerate, style = AeButtonStyle.Ghost)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    AeText("Ask before agents export")
                    AeText(
                        "Agents can save JPEG, PNG, WebP or AVIF copies to CropTop's export folder. Keep this on to approve each one.",
                        style = Ae.type.caption,
                        color = c.textTertiary,
                        maxLines = 3,
                    )
                }
                AeSwitch(ui.settings.confirmExport, ui.onConfirmExport, contentDescription = "Ask before agents export")
            }
            AeText(
                "Anyone with this token can edit your open documents while CropTop is running. Every change appears in History and can be undone.",
                style = Ae.type.caption,
                color = c.textTertiary,
                maxLines = 3,
            )
        }
    }
}
