package app.croptop.core.data

import app.croptop.core.model.Swatch
import app.croptop.core.model.UiPreferences
import app.croptop.core.model.WorkspaceLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface PreferencesRepository {
    val ui: Flow<UiPreferences>

    suspend fun updateUi(transform: (UiPreferences) -> UiPreferences)

    /** The editor's current panel arrangement. */
    val workspace: Flow<WorkspaceLayout>

    suspend fun setWorkspace(layout: WorkspaceLayout)

    /** User-saved named workspaces (Pro: custom workspaces). */
    val customWorkspaces: Flow<List<WorkspaceLayout>>

    suspend fun saveCustomWorkspace(layout: WorkspaceLayout)

    suspend fun deleteCustomWorkspace(name: String)

    /** User keymap as JSON (core:input Keymap format); null = bundled default. */
    val keymapJson: Flow<String?>

    suspend fun setKeymapJson(json: String?)

    /** Last app version whose welcome/what's-new the user has seen (0 = never). */
    val onboardingSeenVersion: Flow<Int>

    suspend fun setOnboardingSeen(versionCode: Int)

    /** Local MCP server: enabled flag, port, pairing token. */
    val agentSettings: Flow<AgentSettings>

    suspend fun setAgentSettings(settings: AgentSettings)

    /** The user's swatches; null = the built-in set. */
    val swatches: Flow<List<Swatch>?> get() = flowOf(null)

    suspend fun setSwatches(swatches: List<Swatch>?) = Unit
}

data class AgentSettings(
    val enabled: Boolean = false,
    val port: Int = 39510,
    val token: String = "",
    /** Ask the user before an agent exports a file (default on). */
    val confirmExport: Boolean = true,
)
