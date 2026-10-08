package app.croptop.core.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReadSnapshotBarrierTest {
    @Test
    fun teardownWaitsUntilEveryNativeSnapshotReaderReleases() {
        val barrier = NativeReadSnapshotBarrier()
        val waiterStarted = CountDownLatch(1)
        val teardownFinished = CountDownLatch(1)
        val complete = AtomicBoolean(false)
        barrier.beginRead()
        barrier.beginRead()
        val teardown = Thread {
            waiterStarted.countDown()
            barrier.awaitReaders()
            complete.set(true)
            teardownFinished.countDown()
        }

        try {
            teardown.start()
            assertTrue(waiterStarted.await(2, TimeUnit.SECONDS))
            assertFalse("teardown must wait while snapshot readers are active", teardownFinished.await(50, TimeUnit.MILLISECONDS))
            barrier.endRead()
            assertFalse("one remaining reader must still hold teardown", teardownFinished.await(50, TimeUnit.MILLISECONDS))
            barrier.endRead()
            assertTrue(teardownFinished.await(2, TimeUnit.SECONDS))
            assertTrue(complete.get())
        } finally {
            // Release any lease left by an assertion failure so the waiter cannot hang.
            runCatching { barrier.endRead() }
            runCatching { barrier.endRead() }
            teardown.join(2_000)
        }
    }
}
