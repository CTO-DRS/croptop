package app.croptop.core.engine

import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Bounded FIFO lane for synchronous document-state commands. Rendering stays on its own worker pool. */
internal class DocumentCommandQueue(
    maxPendingCommands: Int = 128,
    threadName: String = "CropTopDocumentWriter",
) : Closeable {
    private val lifecycleLock = Any()
    private val permits = Semaphore(maxPendingCommands, true)
    private val workerThread = ThreadLocal.withInitial<Boolean> { false }
    private val threadIds = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "$threadName-${threadIds.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    @Volatile
    private var closed = false

    init {
        require(maxPendingCommands > 0) { "maxPendingCommands must be positive" }
    }

    val isClosed: Boolean get() = closed
    internal val isWriterThread: Boolean get() = workerThread.get()

    /** Queues a command and waits for its result; nested calls from the writer run inline. */
    fun <T> run(command: () -> T): T = if (workerThread.get()) command() else await(enqueue(command))

    /** Enqueues one command. Admission blocks when the bounded lane is full. */
    fun <T> enqueue(command: () -> T): Future<T> {
        acquireUninterruptibly(permits)
        try {
            return synchronized(lifecycleLock) {
                if (closed) throw DocumentCommandQueueClosedException()
                val result = CompletableFuture<T>()
                executor.execute {
                    if (result.isCancelled) {
                        permits.release()
                    } else {
                        workerThread.set(true)
                        try {
                            result.complete(command())
                        } catch (failure: Throwable) {
                            result.completeExceptionally(failure)
                        } finally {
                            workerThread.remove()
                            permits.release()
                        }
                    }
                }
                result
            }
        } catch (failure: Throwable) {
            permits.release()
            throw failure
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed) {
                closed = true
                executor.shutdown()
            }
        }
        if (workerThread.get()) return
        var interrupted = false
        while (true) {
            try {
                if (executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun <T> await(future: Future<T>): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (wrapped: ExecutionException) {
                    throw wrapped.cause ?: wrapped
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun acquireUninterruptibly(semaphore: Semaphore) {
        var interrupted = false
        while (true) {
            try {
                semaphore.acquire()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal class DocumentCommandQueueClosedException : IllegalStateException("Document command queue is closed")
