package app.croptop.core.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith

class NativePathOpsBridgeDataTest {
    @Test
    fun pathDataValidatesVerbAndPointCounts() {
        NativePathOpsBridge.PathData(byteArrayOf(0, 1, 1, 4), doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0))
        NativePathOpsBridge.PathData(byteArrayOf(0, 3, 4), doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 1.0, 3.0, 0.0))
        assertFailsWith<IllegalArgumentException> { NativePathOpsBridge.PathData(byteArrayOf(0, 1), doubleArrayOf(0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { NativePathOpsBridge.PathData(byteArrayOf(9), doubleArrayOf()) }
    }
}
