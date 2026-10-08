// Accelerator selection with CPU fallback: NPU, then GPU, then CPU, first one that really loads the model.
package app.croptop.core.ai

import java.io.Closeable
import java.io.File

enum class Delegate { NPU, GPU, CPU }

/** A model loaded on one delegate. [run] takes and returns row-major float tensors with the model's own layout. */
interface ModelSession : Closeable {
    val delegate: Delegate
    fun run(input: FloatArray, inputShape: IntArray): FloatArray

    /** Runs a model with several input tensors, in the model's own input order. */
    fun runAll(inputs: List<FloatArray>, shapes: List<IntArray>): FloatArray =
        throw UnsupportedOperationException("This session runs models with one input")

    /** Runs a model and returns every output tensor, in the model's own output order. */
    fun runOutputs(inputs: List<FloatArray>, shapes: List<IntArray>): List<FloatArray> =
        throw UnsupportedOperationException("This session returns one output")
}

/** Creates sessions; the LiteRT-backed implementation lives in the app layer, tests use fakes. Throws when a delegate cannot load [model]. */
fun interface InferenceBackend {
    fun open(model: File, delegate: Delegate): ModelSession
}

data class SessionAttempt(val delegate: Delegate, val error: String?)

class OpenedSession(val session: ModelSession, val attempts: List<SessionAttempt>)

object DelegatePolicy {
    /** [preferred] filtered to what the device offers, CPU always last (it is the guaranteed fallback). */
    fun order(available: Set<Delegate>, preferred: List<Delegate> = listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU)): List<Delegate> {
        val accelerated = preferred.distinct().filter { it != Delegate.CPU && it in available }
        return accelerated + Delegate.CPU
    }

    /**
     * Opens [model] on the first delegate of [order] that works. A delegate that throws (unsupported op, driver bug) is recorded and
     * skipped; if every delegate fails, the CPU error is thrown with the others attached as suppressed.
     */
    fun open(backend: InferenceBackend, model: File, order: List<Delegate>): OpenedSession {
        require(order.isNotEmpty()) { "no delegates to try" }
        val attempts = mutableListOf<SessionAttempt>()
        var lastError: Exception? = null
        for (delegate in order) {
            try {
                val session = backend.open(model, delegate)
                attempts += SessionAttempt(delegate, null)
                return OpenedSession(session, attempts)
            } catch (e: Exception) {
                attempts += SessionAttempt(delegate, e.message ?: e.javaClass.simpleName)
                lastError?.let { e.addSuppressed(it) }
                lastError = e
            }
        }
        val summary = attempts.joinToString { "${it.delegate}: ${it.error}" }
        throw IllegalStateException("model could not be loaded on any delegate: $summary", lastError)
    }
}
