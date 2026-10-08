package app.croptop.core.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeSession(override val delegate: Delegate) : ModelSession {
    var closed = false
    override fun run(input: FloatArray, inputShape: IntArray) = input
    override fun close() {
        closed = true
    }
}

class DelegatePolicyTest {
    private val model = File("model.tflite")

    @Test
    fun ordersAcceleratorsFirstAndAlwaysEndsOnCpu() {
        assertEquals(listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU), DelegatePolicy.order(setOf(Delegate.NPU, Delegate.GPU, Delegate.CPU)))
        assertEquals(listOf(Delegate.GPU, Delegate.CPU), DelegatePolicy.order(setOf(Delegate.GPU)))
        assertEquals(listOf(Delegate.CPU), DelegatePolicy.order(emptySet()))
        val gpuFirst = DelegatePolicy.order(setOf(Delegate.NPU, Delegate.GPU), preferred = listOf(Delegate.GPU, Delegate.NPU))
        assertEquals(listOf(Delegate.GPU, Delegate.NPU, Delegate.CPU), gpuFirst)
    }

    @Test
    fun aDelegateThatThrowsFallsBackToTheNextAndTheAttemptsAreRecorded() {
        val opened = mutableListOf<Delegate>()
        val backend = InferenceBackend { _, delegate ->
            opened += delegate
            when (delegate) {
                Delegate.NPU -> throw IllegalStateException("op 42 unsupported")
                Delegate.GPU -> throw IllegalStateException("driver rejected the kernel")
                Delegate.CPU -> FakeSession(delegate)
            }
        }
        val result = DelegatePolicy.open(backend, model, listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU))
        assertEquals(Delegate.CPU, result.session.delegate)
        assertEquals(listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU), opened)
        assertEquals(listOf("op 42 unsupported", "driver rejected the kernel", null), result.attempts.map { it.error })
    }

    @Test
    fun theFirstWorkingDelegateWinsAndLaterOnesAreNotTried() {
        val opened = mutableListOf<Delegate>()
        val backend = InferenceBackend { _, delegate ->
            opened += delegate
            FakeSession(delegate)
        }
        val result = DelegatePolicy.open(backend, model, listOf(Delegate.GPU, Delegate.CPU))
        assertEquals(Delegate.GPU, result.session.delegate)
        assertEquals(listOf(Delegate.GPU), opened)
    }

    @Test
    fun whenEveryDelegateFailsTheErrorNamesThemAll() {
        val backend = InferenceBackend { _, delegate -> throw IllegalStateException("$delegate broke") }
        val error = assertFailsWith<IllegalStateException> { DelegatePolicy.open(backend, model, listOf(Delegate.GPU, Delegate.CPU)) }
        assertTrue("GPU: GPU broke" in error.message.orEmpty() && "CPU: CPU broke" in error.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { DelegatePolicy.open(backend, model, emptyList()) }
    }
}
