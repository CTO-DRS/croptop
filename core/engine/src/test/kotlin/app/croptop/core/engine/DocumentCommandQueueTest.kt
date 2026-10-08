package app.croptop.core.engine

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCommandQueueTest {
    @Test
    fun commandsRunFifoAndNestedCallsStayOnTheWriter() {
        val queue = DocumentCommandQueue(threadName = "DocumentCommandQueueTest")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        try {
            val first = queue.enqueue {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                order += 1
                Thread.currentThread().name
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = queue.enqueue {
                order += 2
                queue.run {
                    order += 3
                    Thread.currentThread().name
                }
            }
            release.countDown()
            val writerName = first.get(2, TimeUnit.SECONDS)
            assertEquals(writerName, second.get(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), order)
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun commandFailuresReachTheSynchronousCallerAndQueueRemainsUsable() {
        val queue = DocumentCommandQueue(threadName = "DocumentCommandFailureTest")
        try {
            val failure = runCatching { queue.run<Int> { error("command failed") } }.exceptionOrNull()
            assertEquals("command failed", failure?.message)
            assertEquals(7, queue.run { 7 })
        } finally {
            queue.close()
        }
    }

    @Test
    fun canceledQueuedCommandReleasesItsBoundedAdmissionSlotAfterDrain() {
        val queue = DocumentCommandQueue(maxPendingCommands = 2, threadName = "DocumentCommandCancelTest")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val thirdSubmitted = CountDownLatch(1)
        val thirdAdmitted = CountDownLatch(1)
        val third = AtomicReference<Future<Int>>()
        val submitter = Thread {
            thirdSubmitted.countDown()
            third.set(
                queue.enqueue {
                    thirdAdmitted.countDown()
                    3
                },
            )
        }
        try {
            val first = queue.enqueue {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                1
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val canceled = queue.enqueue {
                secondRan.set(true)
                2
            }
            assertTrue(canceled.cancel(false))

            submitter.start()
            assertTrue(thirdSubmitted.await(2, TimeUnit.SECONDS))
            assertFalse("the running and queued commands occupy both slots", thirdAdmitted.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertEquals(1, first.get(2, TimeUnit.SECONDS))
            assertTrue(thirdAdmitted.await(2, TimeUnit.SECONDS))
            assertEquals(3, third.get()?.get(2, TimeUnit.SECONDS))
            assertTrue(canceled.isCancelled)
            assertFalse("a canceled queued command must not execute", secondRan.get())
        } finally {
            release.countDown()
            queue.close()
            submitter.join(2_000)
        }
    }
}
