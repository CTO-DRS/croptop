package app.croptop.core.engine

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCodecExtraIccBridgeTest {
    @Test
    fun tiffAndFlattenedPsdDecodeReturnRetainedIccBytes() {
        val engine = NativeEngine()
        val source = engine.createTileStore(width = 8, height = 8, format = 0)
        val destination = engine.createTileStore(width = 8, height = 8, format = 0)
        val profile = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3)
        try {
            val icc = profile.iccBytes()
            assertTrue(icc.isNotEmpty())

            for (kind in listOf(NativeCodecExtraBridge.Kind.TIFF, NativeCodecExtraBridge.Kind.PSD)) {
                val output = ByteArrayOutputStream()
                NativeCodecExtraBridge.encode(source, kind, output, icc)
                val encoded = output.toByteArray()
                val probe = NativeCodecExtraBridge.probe(encoded, encoded.size)
                assertEquals(kind, probe.kind)

                val decoded = NativeCodecExtraBridge.decodeWithProfile(encoded, encoded.size, destination)
                assertArrayEquals("$kind ICC payload", icc, checkNotNull(decoded.iccProfile))
            }
        } finally {
            profile.close()
            engine.destroyTileStore(destination)
            engine.destroyTileStore(source)
            engine.close()
        }
    }
}
