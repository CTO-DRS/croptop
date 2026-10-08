package app.croptop.feature.library.batch

import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.OpenOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [rawSettings] is the photo's saved Develop recipe, applied when the source is a RAW (null = the file's own defaults). */
data class BatchItem(
    val uri: String,
    val name: String,
    val rawSettings: app.croptop.core.model.DevelopSettings? = null,
    /** The Library's title, caption, creator, copyright and keywords for this photo, written into the export. */
    val iptc: app.croptop.core.engine.api.IptcMetadata? = null,
)

data class BatchFailure(val name: String, val reason: String)

/** The export queue as the UI sees it. [Finished] stays until [BatchExportController.dismiss]. */
sealed interface BatchExportState {
    data object Idle : BatchExportState

    data class Running(
        val total: Int,
        val completed: Int,
        val current: String?,
        val failures: List<BatchFailure>,
    ) : BatchExportState {
        val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
    }

    data class Finished(val total: Int, val succeeded: Int, val failures: List<BatchFailure>, val cancelled: Boolean) : BatchExportState
}

/** Creates destination files inside the folder the user picked. Implementations must never overwrite an existing file. */
interface BatchOutput {
    /** A new file named after [baseName] with [format]'s extension, or null when it cannot be created. */
    suspend fun create(folder: String, baseName: String, format: ExportFormat): ExportTarget.ContentUri?
}

fun ExportFormat.extension(): String = when (this) {
    ExportFormat.PNG -> "png"
    ExportFormat.JPEG -> "jpg"
    ExportFormat.WEBP -> "webp"
    ExportFormat.TIFF -> "tif"
    ExportFormat.PSD -> "psd"
    ExportFormat.AVIF -> "avif"
    ExportFormat.ULTRA_HDR -> "jpg"
}

fun ExportFormat.mimeType(): String = when (this) {
    ExportFormat.PNG -> "image/png"
    ExportFormat.JPEG -> "image/jpeg"
    ExportFormat.WEBP -> "image/webp"
    ExportFormat.TIFF -> "image/tiff"
    ExportFormat.PSD -> "image/vnd.adobe.photoshop"
    ExportFormat.AVIF -> "image/avif"
    ExportFormat.ULTRA_HDR -> "image/jpeg"
}

/**
 * Exports many catalog photos one after another with a single preset. The queue lives in an application-scoped
 * coroutine, so leaving the Library does not stop it; [cancel] stops after the file in flight. One file failing never stops
 * the rest: it is listed in the final report.
 */
@Singleton
class BatchExportController @Inject constructor(
    private val engine: Engine,
    private val output: BatchOutput,
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var cancelRequested = false
    private val _state = MutableStateFlow<BatchExportState>(BatchExportState.Idle)
    val state: StateFlow<BatchExportState> = _state.asStateFlow()

    /** Test hook: run on [dispatcher] instead of Dispatchers.Default. */
    internal fun useDispatcher(dispatcher: CoroutineDispatcher) {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    /** Starts a batch; ignored while another one is running. Returns whether it started. */
    @Synchronized
    fun start(items: List<BatchItem>, options: ExportOptions, folder: String): Boolean {
        if (items.isEmpty() || _state.value is BatchExportState.Running) return false
        cancelRequested = false
        _state.value = BatchExportState.Running(items.size, 0, items.first().name, emptyList())
        job = scope.launch { run(items, options, folder) }
        return true
    }

    /** Stops after the file in flight; the final report still lists what was exported. */
    fun cancel() {
        cancelRequested = true
    }

    fun dismiss() {
        if (_state.value is BatchExportState.Finished) _state.value = BatchExportState.Idle
    }

    private suspend fun run(items: List<BatchItem>, options: ExportOptions, folder: String) {
        val failures = mutableListOf<BatchFailure>()
        var succeeded = 0
        var cancelled = false
        for ((index, item) in items.withIndex()) {
            if (cancelRequested) {
                cancelled = true
                break
            }
            _state.value = BatchExportState.Running(items.size, index, item.name, failures.toList())
            try {
                exportOne(item, options, folder)
                succeeded++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += BatchFailure(item.name, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
        }
        _state.value = BatchExportState.Finished(items.size, succeeded, failures.toList(), cancelled)
    }

    private suspend fun exportOne(item: BatchItem, options: ExportOptions, folder: String) {
        val target = output.create(folder, item.name.substringBeforeLast('.').ifBlank { "Image" }, options.format)
            ?: error("Could not create the output file")
        val document = engine.open(DocumentSource.ContentUri(item.uri), OpenOptions(rawDevelopSettings = item.rawSettings))
        try {
            document.export(target, if (item.iptc != null && !options.stripMetadata) options.copy(iptc = item.iptc) else options)
        } finally {
            document.close()
        }
    }
}
