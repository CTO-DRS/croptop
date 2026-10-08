package app.croptop.core.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.AgentSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class AgentViewModel @Inject constructor(private val controller: AgentController) : ViewModel() {
    val status: StateFlow<AgentStatus> = controller.status
    val settings: StateFlow<AgentSettings> = controller.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AgentSettings())

    fun setEnabled(on: Boolean) = controller.setEnabled(on)

    fun regenerateToken() = controller.regenerateToken()

    fun disconnect() = controller.disconnect()

    /** The export an agent is waiting on (the editor shows Allow / Deny). */
    val pendingExport = controller.exports.pending

    fun answerExport(allow: Boolean) = controller.exports.respond(allow)

    fun setConfirmExport(on: Boolean) = controller.setConfirmExport(on)

    /** Ready-to-paste MCP client config (Claude Desktop / Gemini CLI style). */
    fun clientConfig(s: AgentSettings): String = """
        {
          "mcpServers": {
            "croptop": {
              "url": "http://127.0.0.1:${s.port}/mcp",
              "headers": { "Authorization": "Bearer ${s.token}" }
            }
          }
        }
    """.trimIndent()
}
