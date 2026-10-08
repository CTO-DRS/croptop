package app.croptop.core.engine

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * One document-owned FIFO worker for brush sample chunks and terminal commits.
 * Chunk admission is bounded by both count and copied payload bytes. Queue overflow poisons that
 * stroke so an accepted prefix can never be committed as a partial stroke.
 */
internal class StrokeWorkQueue(
    private val maxPendingChunks: Int = 64,
    private val maxPendingBytes: Int = 2 * 1024 * 1024,
    threadName: String = "CropTopStrokeWorker",
) : Closeable {
    private enum class LaneState { OPEN, DRAINING, CANCELLED, FAILED, CLOSED }

    private val lock = Any()
    private val threadIds = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "$threadName-${threadIds.incrementAndGet()}").apply { isDaemon = true }
        },
    )
    private val lanes = linkedSetOf<Lane>()
    private var pendingChunks = 0
    private var pendingBytes = 0
    private var closed = false

    init {
        require(maxPendingChunks > 0) { "maxPendingChunks must be positive" }
        require(maxPendingBytes > 0) { "maxPendingBytes must be positive" }
    }

    fun newLane(dispose: () -> Unit): Lane = synchronized(lock) {
        check(!closed) { "Stroke work queue is closed" }
        Lane(dispose).also(lanes::add)
    }

    inner class Lane internal constructor(private val dispose: () -> Unit) {
        private var state = LaneState.OPEN
        private var failure: Throwable? = null
        private var cancelled = false
        private var terminalScheduled = false
        private var commitStarted = false
        private var terminalFuture: Future<*>? = null

        internal val terminalQueued: Boolean
            get() = synchronized(lock) { terminalScheduled }

        internal val isCancelled: Boolean
            get() = synchronized(lock) { cancelled }

        /** Copies [payload] before accepting it. Returns false and poisons the lane on overflow. */
        fun add(payload: ByteArray, consume: (ByteArray) -> Unit): Boolean =
            addCopied(payload.size.toLong(), { payload.copyOf() }, consume)

        /** Copies a float sample chunk once; bytes are still accounted against the queue bound. */
        fun addFloats(payload: FloatArray, consume: (FloatArray) -> Unit): Boolean =
            addCopied(payload.size.toLong() * Float.SIZE_BYTES, { payload.copyOf() }, consume)

        private fun <T> addCopied(payloadByteCount: Long, copy: () -> T, consume: (T) -> Unit): Boolean {
            if (payloadByteCount > maxPendingBytes) {
                synchronized(lock) {
                    if (closed || state != LaneState.OPEN) return false
                    failure = StrokeQueueOverflowException(maxPendingChunks, maxPendingBytes)
                    state = LaneState.FAILED
                }
                return false
            }
            val immutablePayload = copy()
            synchronized(lock) {
                if (closed || state != LaneState.OPEN) return false
                if (pendingChunks >= maxPendingChunks || payloadByteCount > maxPendingBytes - pendingBytes) {
                    failure = StrokeQueueOverflowException(maxPendingChunks, maxPendingBytes)
                    state = LaneState.FAILED
                    return false
                }
                pendingChunks++
                pendingBytes += payloadByteCount.toInt()
                try {
                    worker.execute {
                        try {
                            val shouldConsume = synchronized(lock) {
                                state == LaneState.OPEN || state == LaneState.DRAINING
                            }
                            if (shouldConsume) consume(immutablePayload)
                        } catch (thrown: Throwable) {
                            synchronized(lock) {
                                if (failure == null) failure = thrown
                                state = LaneState.FAILED
                            }
                        } finally {
                            synchronized(lock) {
                                pendingChunks--
                                pendingBytes -= payloadByteCount.toInt()
                            }
                        }
                    }
                } catch (rejected: RuntimeException) {
                    pendingChunks--
                    pendingBytes -= payloadByteCount.toInt()
                    failure = rejected
                    state = LaneState.FAILED
                    return false
                }
                return true
            }
        }

        /** Drains accepted chunks, commits once on the FIFO worker, then disposes the native lane. */
        fun <T> finish(commit: () -> T): T {
            val (future, wasCancelled) = synchronized(lock) {
                if (terminalScheduled) {
                    check(cancelled) { "Stroke lane already has a terminal operation" }
                    return@synchronized checkNotNull(terminalFuture) to true
                }
                check(state != LaneState.CLOSED) { "Stroke lane is closed" }
                if (state == LaneState.OPEN) state = LaneState.DRAINING
                terminalScheduled = true
                terminalFuture = worker.submit(
                    Callable {
                        try {
                            val problem = synchronized(lock) {
                                when {
                                    failure != null -> failure

                                    cancelled -> StrokeWorkCancelledException()

                                    state != LaneState.DRAINING -> IllegalStateException("Stroke lane is not drainable")

                                    else -> {
                                        commitStarted = true
                                        null
                                    }
                                }
                            }
                            if (problem != null) throw problem
                            commit()
                        } catch (thrown: Throwable) {
                            synchronized(lock) {
                                if (failure == null && thrown !is StrokeWorkCancelledException) failure = thrown
                                if (!cancelled) state = LaneState.FAILED
                            }
                            throw thrown
                        } finally {
                            disposeAndClose()
                        }
                    },
                )
                checkNotNull(terminalFuture) to false
            }
            return try {
                val value = future.get()
                if (wasCancelled) throw StrokeWorkCancelledException()
                @Suppress("UNCHECKED_CAST")
                (value as T)
            } catch (wrapped: ExecutionException) {
                throw wrapped.cause ?: wrapped
            }
        }

        /** Non-blocking cancellation. Queued chunks observe cancellation and skip; dispose is FIFO. */
        fun cancel() {
            synchronized(lock) {
                if (state == LaneState.CLOSED || commitStarted) return
                cancelled = true
                state = LaneState.CANCELLED
                if (terminalScheduled) return
                terminalScheduled = true
                terminalFuture = worker.submit { disposeAndClose() }
            }
        }

        private fun disposeAndClose() {
            try {
                dispose()
            } finally {
                synchronized(lock) {
                    state = LaneState.CLOSED
                    lanes.remove(this)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                lanes.toList().forEach { it.cancel() }
                worker.shutdown()
            }
        }
        var interrupted = false
        while (true) {
            try {
                if (worker.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal class StrokeQueueOverflowException(maxChunks: Int, maxBytes: Int) :
    IllegalStateException("Stroke queue is full (limit $maxChunks chunks / $maxBytes bytes); the whole stroke was rejected")

internal class StrokeWorkCancelledException : IllegalStateException("Stroke was cancelled before commit")
