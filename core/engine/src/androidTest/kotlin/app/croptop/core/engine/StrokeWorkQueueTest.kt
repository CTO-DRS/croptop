package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrokeWorkQueueTest {
    @Test
    fun floatChunksAreCopiedAndCountedByTheirByteSize() {
        val queue = StrokeWorkQueue(maxPendingChunks = 2, maxPendingBytes = 28, threadName = "StrokeFloatQueueTest")
        val lane = queue.newLane { }
        val source = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f)
        val consumed = AtomicReference<FloatArray?>()
        try {
            assertTrue(lane.addFloats(source) { consumed.set(it) })
            source[0] = 99f
            lane.finish { Unit }
            assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), checkNotNull(consumed.get()), 0f)
        } finally {
            queue.close()
        }
    }

    @Test
    fun chunksAreCopiedAndConsumedBeforeSingleTerminalCommit() {
        val queue = StrokeWorkQueue(threadName = "StrokeOrderTest")
        val lane = queue.newLane { }
        val events = Collections.synchronizedList(mutableListOf<Int>())
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)

        assertTrue(lane.add(first) { events += it[0].toInt() })
        first[0] = 99
        assertTrue(lane.add(second) { events += it[0].toInt() })
        lane.finish {
            events += 3
            "committed"
        }.also { assertEquals("committed", it) }

        assertEquals(listOf(1, 2, 3), events)
        queue.close()
    }

    @Test
    fun queueOverflowRejectsWholeStrokeAndNeverRunsCommit() {
        val queue = StrokeWorkQueue(maxPendingChunks = 1, maxPendingBytes = 32, threadName = "StrokeOverflowTest")
        val disposed = AtomicBoolean()
        val committed = AtomicBoolean()
        val lane = queue.newLane { disposed.set(true) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(
                lane.add(byteArrayOf(1)) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                },
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(lane.add(byteArrayOf(2)) { })
            release.countDown()

            val failure = captureFailure {
                lane.finish {
                    committed.set(true)
                }
            }
            assertTrue(failure is StrokeQueueOverflowException)
            assertFalse(committed.get())
            assertTrue(disposed.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun oversizedChunkPoisonsLaneBeforeWorkIsAccepted() {
        val queue = StrokeWorkQueue(maxPendingChunks = 8, maxPendingBytes = 4, threadName = "StrokeByteLimitTest")
        val consumed = AtomicBoolean()
        val lane = queue.newLane { }
        try {
            assertFalse(lane.add(ByteArray(5)) { consumed.set(true) })
            assertTrue(captureFailure { lane.finish { error("must not commit") } } is StrokeQueueOverflowException)
            assertFalse(consumed.get())
        } finally {
            queue.close()
        }
    }

    @Test
    fun cancelSkipsQueuedChunksAndDisposesAfterRunningChunkReturns() {
        val queue = StrokeWorkQueue(threadName = "StrokeCancelTest")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedCalls = AtomicInteger()
        val disposed = AtomicBoolean()
        val lane = queue.newLane { disposed.set(true) }
        try {
            assertTrue(
                lane.add(byteArrayOf(1)) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                },
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(lane.add(byteArrayOf(2)) { queuedCalls.incrementAndGet() })

            lane.cancel()
            release.countDown()
            queue.close()

            assertEquals(0, queuedCalls.get())
            assertTrue(disposed.get())
            assertTrue(captureFailure { lane.finish { error("must not commit") } } is StrokeWorkCancelledException)
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun chunkFailurePropagatesAtFinishAndPreventsCommit() {
        val queue = StrokeWorkQueue(threadName = "StrokeFailureTest")
        val expected = IllegalArgumentException("sample decode failed")
        val committed = AtomicBoolean()
        val lane = queue.newLane { }
        try {
            assertTrue(lane.add(byteArrayOf(1)) { throw expected })
            val actual = captureFailure {
                lane.finish {
                    committed.set(true)
                }
            }
            assertTrue(actual is IllegalArgumentException)
            assertEquals(expected.message, actual.message)
            assertFalse(committed.get())
        } finally {
            queue.close()
        }
    }

    @Test
    fun closeCancelsQueuedFinishAndWaitsForRunningLaneBeforeReturning() {
        val queue = StrokeWorkQueue(threadName = "StrokeCloseTest")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val disposed = AtomicBoolean()
        val committed = AtomicBoolean()
        val finishFailure = AtomicReference<Throwable?>()
        val lane = queue.newLane { disposed.set(true) }
        val finisher = Thread {
            try {
                lane.finish {
                    committed.set(true)
                    Unit
                }
            } catch (failure: Throwable) {
                finishFailure.set(failure)
            }
        }
        val closer = Thread {
            closeStarted.countDown()
            queue.close()
        }
        try {
            assertTrue(
                lane.add(byteArrayOf(1)) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                },
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(lane.add(byteArrayOf(2)) { })
            finisher.start()
            val scheduleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!lane.terminalQueued && System.nanoTime() < scheduleDeadline) Thread.yield()
            assertTrue("finish terminal should be queued", lane.terminalQueued)

            closer.start()
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            val cancelDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!lane.isCancelled && System.nanoTime() < cancelDeadline) Thread.yield()
            assertTrue("document close should cancel the queued terminal commit", lane.isCancelled)
            release.countDown()
            closer.join(TimeUnit.SECONDS.toMillis(5))
            finisher.join(TimeUnit.SECONDS.toMillis(5))

            assertFalse(closer.isAlive)
            assertFalse(finisher.isAlive)
            assertFalse(committed.get())
            assertTrue(finishFailure.get() is StrokeWorkCancelledException)
            assertTrue(disposed.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    private fun captureFailure(block: () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected operation to fail")
    }
}
