package app.croptop.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The Kotlin table must stay in step with `kCounts` in filter_dispatch.cpp (the native test pins the same total). */
class NativeFiltersBridgeTableTest {
    @Test
    fun idsAreDenseAndParameterCountsMatchTheNativeTable() {
        assertEquals((0 until 33).toList(), NativeFiltersBridge.Kind.entries.map { it.id })
        assertEquals(NATIVE_PARAMETER_TOTAL, NativeFiltersBridge.Kind.entries.sumOf { it.parameters })
    }

    @Test
    fun wrongParameterCountFailsBeforeJni() {
        assertFailsWith<IllegalArgumentException> { NativeFiltersBridge.apply(1L, NativeFiltersBridge.Kind.GAUSSIAN_BLUR) }
    }

    private companion object {
        const val NATIVE_PARAMETER_TOTAL = 81
    }
}
