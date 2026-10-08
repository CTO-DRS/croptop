package app.croptop.core.ai

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the download UI shows for one model. */
data class ModelRow(
    val spec: ModelSpec,
    val status: Status,
) {
    sealed interface Status {
        data object NotInstalled : Status
        data class Partial(val bytesOnDisk: Long) : Status
        data class Downloading(val done: Long, val total: Long) : Status {
            val fraction: Float get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        }
        data object Installed : Status
        data class Failed(val reason: String) : Status
    }
}

data class ModelManagerState(val rows: List<ModelRow>, val usedBytes: Long)

/**
 * Drives [ModelStore] for the UI: one background install per model, cancellable, with live progress and storage used.
 * The store is blocking, so installs run on [io]; state updates are published through [state].
 */
/** The part of the model manager the editor needs: live download state and install/cancel. */
interface ModelDownloads {
    val state: StateFlow<ModelManagerState>

    fun install(id: String)

    fun cancel(id: String)
}

class ModelManager(
    private val store: ModelStore,
    private val registry: ModelRegistry,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ModelDownloads {
    private val jobs = HashMap<String, Job>()
    private val cancelRequested = HashSet<String>()
    private val running = HashSet<String>()
    private val live = MutableStateFlow<Map<String, ModelRow.Status>>(emptyMap())
    private val usedBytes = MutableStateFlow(store.usedBytes())

    private val _state = MutableStateFlow(snapshot())
    override val state: StateFlow<ModelManagerState> = _state.asStateFlow()

    private fun snapshot(): ModelManagerState {
        val overrides = live.value
        val rows = registry.models.sortedBy { it.displayName }.map { spec ->
            ModelRow(spec, overrides[spec.id] ?: diskStatus(spec))
        }
        return ModelManagerState(rows, usedBytes.value)
    }

    private fun diskStatus(spec: ModelSpec): ModelRow.Status = when (val s = store.state(spec)) {
        ModelState.Installed -> ModelRow.Status.Installed
        ModelState.NotInstalled -> ModelRow.Status.NotInstalled
        is ModelState.Partial -> ModelRow.Status.Partial(s.bytesOnDisk)
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun setLive(id: String, status: ModelRow.Status?) {
        live.update { if (status == null) it - id else it + (id to status) }
        publish()
    }

    /** The installed file [name] of model [id], or null while it is missing or incomplete. */
    fun file(id: String, name: String): File? = registry[id]?.takeIf { store.state(it) == ModelState.Installed }?.let { store.file(it, name) }

    /** The registered spec of [id] (what a download would fetch), or null. */
    fun spec(id: String): ModelSpec? = registry[id]

    fun isInstalled(id: String): Boolean = registry[id]?.let { store.state(it) == ModelState.Installed } == true

    /** Starts (or resumes) the download of [id]. No-op when it is unknown, installed, or already downloading. */
    override fun install(id: String) {
        val spec = registry[id] ?: return
        synchronized(jobs) {
            if (id in running || store.state(spec) == ModelState.Installed) return
            cancelRequested.remove(id)
            running += id
            setLive(id, ModelRow.Status.Downloading(0, spec.totalBytes))
            jobs[id] = scope.launch(io) {
                val result = store.install(
                    spec,
                    onProgress = { done, total -> setLive(id, ModelRow.Status.Downloading(done, total)) },
                    cancelled = { synchronized(jobs) { id in cancelRequested } },
                )
                usedBytes.value = store.usedBytes()
                synchronized(jobs) { running -= id }
                setLive(
                    id,
                    when (result) {
                        InstallResult.Installed, InstallResult.Cancelled -> null
                        is InstallResult.Failed -> ModelRow.Status.Failed(result.reason)
                    },
                )
            }
        }
    }

    /** Stops the download of [id]; the partial file is kept so a later install resumes. */
    override fun cancel(id: String) {
        synchronized(jobs) { if (id in running) cancelRequested += id }
    }

    /** Deletes the files of [id] (cancelling a running download first). */
    fun delete(id: String) {
        val spec = registry[id] ?: return
        val pending = synchronized(jobs) {
            if (id in running) cancelRequested += id
            jobs[id]?.takeIf { it.isActive }
        }
        scope.launch(io) {
            pending?.join()
            store.delete(spec)
            synchronized(jobs) { cancelRequested -= id }
            usedBytes.value = store.usedBytes()
            setLive(id, null)
        }
    }
}

/** HTTPS downloader with `Range` resume. */
class HttpDownloader(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000) : Downloader {
    override fun open(url: String, resumeFrom: Long): DownloadStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            throw IOException("server returned HTTP $code")
        }
        return DownloadStream(connection.inputStream, resumed = code == HttpURLConnection.HTTP_PARTIAL)
    }
}

/** "123 MB" style sizes for the UI. */
fun formatModelBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
