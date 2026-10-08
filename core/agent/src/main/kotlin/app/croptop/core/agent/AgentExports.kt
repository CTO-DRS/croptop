package app.croptop.core.agent

import android.content.Context
import app.croptop.core.data.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** An agent wants to write a file; the editor shows [description] with Allow / Deny. */
class AgentExportRequest(val description: String, internal val answer: CompletableDeferred<Boolean>)

/**
 * Where agents may write exports (the app's cache, never a user-chosen path) and the confirm-before-export gate
 * (Settings ▸ AI agents). Files land in [directory] and can be fetched or shared by the user from there.
 */
/** What [AgentTools] needs to export: a folder and a way to ask the user. */
interface ExportGate {
    val directory: File

    /** True when the export may proceed. */
    suspend fun confirm(description: String): Boolean
}

@Singleton
class AgentExports @Inject constructor(
    @ApplicationContext context: Context,
    private val prefs: PreferencesRepository,
) : ExportGate {
    override val directory: File = File(context.cacheDir, "agent-exports")

    private val _pending = MutableStateFlow<AgentExportRequest?>(null)
    val pending: StateFlow<AgentExportRequest?> = _pending

    /** True when the export may proceed: the setting is off, or the user allowed it within [TIMEOUT_MS]. */
    override suspend fun confirm(description: String): Boolean {
        if (!prefs.agentSettings.first().confirmExport) return true
        val request = AgentExportRequest(description, CompletableDeferred())
        _pending.value = request
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { request.answer.await() } ?: false
        } finally {
            if (_pending.value === request) _pending.value = null
        }
    }

    fun respond(allow: Boolean) {
        _pending.value?.answer?.complete(allow)
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
