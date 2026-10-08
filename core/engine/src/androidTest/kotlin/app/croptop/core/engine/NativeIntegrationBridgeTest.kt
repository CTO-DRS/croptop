package app.croptop.core.engine

import app.croptop.core.engine.api.SampleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeIntegrationBridgeTest {
    @Test
    fun smartObjectSessionRendersAndReleasesRegisteredBridge() {
        val engine = NativeEngine()
        val source = engine.createTileStore(16, 16, 0)
        try {
            assertTrue(source != 0L)
            NativeSmartObjectBridge.Session(source).use { session ->
                session.setTransforms(listOf(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
                val filter = session.addGaussianBlur(1f)
                assertEquals(1, session.filterCount)
                session.configureFilter(filter, enabled = false)
                val rendered = session.render(16, 16)
                try {
                    assertTrue(rendered != 0L)
                    val sample = engine.samplePixels(
                        listOf(NativeRenderLayer(rendered, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true)),
                        SampleMode.ACTIVE_LAYER,
                        primaryLayerId = 1,
                        x = 8,
                        y = 8,
                        size = 1,
                    )
                    assertNotNull(sample)
                    assertEquals(0f, checkNotNull(sample).a, 0f)
                } finally {
                    engine.destroyTileStore(rendered)
                }
                session.removeFilter(filter)
                assertEquals(0, session.filterCount)
            }
        } finally {
            engine.destroyTileStore(source)
            engine.close()
        }
    }

    @Test
    fun lensProfileDatabaseLoadsAndLooksUpRegisteredBridge() {
        val engine = NativeEngine()
        try {
            NativeLensProfileBridge.Database().use { database ->
                database.addXml(
                    """
                    <lensdatabase version="1">
                      <camera><maker>Canon</maker><model>EOS 90D</model><mount>EF-S</mount><cropfactor>1.6</cropfactor></camera>
                      <lens><maker>Canon</maker><model>EF 50mm</model><mount>EF</mount><cropfactor>1.0</cropfactor>
                        <calibration><distortion model="poly3" focal="50" k1="-0.012"/></calibration>
                      </lens>
                    </lensdatabase>
                    """.trimIndent().toByteArray(),
                )
                assertEquals(1, database.lensCount)
                val lens = checkNotNull(database.findLens("Canon", "EF 50mm"))
                assertEquals(1, lens.distortionEntries)
                assertEquals(1.6f, checkNotNull(database.cameraCropFactor("Canon", "EOS 90D")), 0.001f)
            }
        } finally {
            engine.close()
        }
    }
}
