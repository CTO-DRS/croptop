// Opens the downloaded models on a delegate and caches the one in use. A model that is not on the device
// yet is reported as "needs download" with its size, so the UI can show the first-use sheet instead of failing.
package app.croptop.core.ai

import java.io.File

/** What the AI actions need from a runtime: availability of models and a session to run one in. */
interface ModelRunner {
    fun availability(vararg modelIds: String): AiRuntime.Availability

    fun <T> use(id: String, block: (ModelSession) -> T): T
}

class AiRuntime(
    private val manager: ModelManager,
    private val backend: InferenceBackend,
    private val delegates: List<Delegate> = listOf(Delegate.CPU),
) : ModelRunner {
    /** What is needed before [models] can run. */
    sealed interface Availability {
        data object Ready : Availability

        /** These models must be downloaded first: [bytes] is the total still to fetch. */
        class NeedsDownload(val specs: List<ModelSpec>, val bytes: Long) : Availability

        class Unavailable(val reason: String) : Availability
    }

    private var cachedId: String? = null
    private var cached: ModelSession? = null

    override fun availability(vararg modelIds: String): Availability {
        val missing = mutableListOf<ModelSpec>()
        for (id in modelIds) {
            val spec = manager.spec(id) ?: return Availability.Unavailable("The $id model is not part of this build")
            if (!manager.isInstalled(id)) missing += spec
        }
        return if (missing.isEmpty()) Availability.Ready else Availability.NeedsDownload(missing, missing.sumOf { it.totalBytes })
    }

    /** Runs [block] with a session for model [id] (first file of the model); the previous model's session is released first. */
    @Synchronized
    override fun <T> use(id: String, block: (ModelSession) -> T): T {
        if (cachedId != id) release()
        val session = cached ?: open(id).also {
            cached = it
            cachedId = id
        }
        return block(session)
    }

    @Synchronized
    fun release() {
        runCatching { cached?.close() }
        cached = null
        cachedId = null
    }

    private fun open(id: String): ModelSession {
        val spec = manager.spec(id) ?: error("The $id model is not part of this build")
        val file: File = manager.file(id, spec.files.first().name) ?: error("The ${spec.displayName} model is not downloaded")
        return DelegatePolicy.open(backend, file, DelegatePolicy.order(delegates.toSet(), delegates)).session
    }
}
