package app.croptop.core.agent

import app.croptop.core.data.AgentSettings
import app.croptop.core.data.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the MCP server lifecycle: runs while the setting is on (and the user has Pro), restarts on
 * port/token change. [disconnect] turns it off (the "Disconnect" button on the Agent chip).
 */
@Singleton
class AgentController @Inject constructor(
    private val prefs: PreferencesRepository,
    docs: OpenDocuments,
    val exports: AgentExports,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val server = McpServer(McpProtocol(AgentTools(docs, exports), docs))
    val status: StateFlow<AgentStatus> = server.status
    val settings = prefs.agentSettings

    init {
        scope.launch { prefs.agentSettings.distinctUntilChanged().collect { apply(it) } }
    }

    private suspend fun apply(s: AgentSettings) {
        var settings = s
        if (settings.enabled && settings.token.isBlank()) {
            settings = settings.copy(token = McpServer.newToken())
            prefs.setAgentSettings(settings)
            return // the collector re-applies with the token
        }
        if (settings.enabled) {
            val st = server.status.value
            if (!st.running || st.port != settings.port) server.start(settings.port, settings.token)
        } else {
            server.stop()
        }
    }

    fun setEnabled(on: Boolean) = scope.launch { prefs.setAgentSettings(prefs.agentSettings.first().copy(enabled = on)) }

    fun setConfirmExport(on: Boolean) = scope.launch { prefs.setAgentSettings(prefs.agentSettings.first().copy(confirmExport = on)) }

    fun regenerateToken() = scope.launch {
        val s = prefs.agentSettings.first()
        prefs.setAgentSettings(s.copy(token = McpServer.newToken()))
        if (s.enabled) {
            server.stop() // force restart with the new token
        }
    }

    fun disconnect() = setEnabled(false)
}
