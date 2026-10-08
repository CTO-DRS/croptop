package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LensfunDatabaseAssetTest {
    @Test
    fun pinnedDatabaseXmlAndLicenseArePackagedAndDiscoverable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = context.assets.list("lensfun").orEmpty().toSet()
        val xmlFiles = files.filter { it.endsWith(".xml") }

        assertEquals("pinned database XML file count", 56, xmlFiles.size)
        assertTrue("packaged CC BY-SA license", "COPYING.CC_BY-SA_3.0" in files)
        val manifest = context.assets.open("lensfun/README.txt").bufferedReader().use { it.readText() }
        assertTrue(manifest.contains("bbd4332a9ec566fd9aa548c9e0d8ced238c56261"))

        val canonXml = context.assets.open("lensfun/slr-canon.xml").bufferedReader().use { it.readText() }
        assertTrue(canonXml.contains("<lensdatabase version=\"2\">"))
        assertTrue(canonXml.contains("<maker>Canon</maker>"))
        assertTrue(canonXml.contains("<model>Canon EOS 5DS</model>"))

        val engine = NativeEngine(context) // loads the native library before exercising its bridge
        try {
            NativeLensProfileBridge.fromAssets(context).use { database ->
                assertTrue("the bundled snapshot should expose its full lens collection", database.lensCount > 1_500)
                assertTrue(database.findLens("Canon", "Canon EF 16-35mm f/2.8L II USM") != null)
                assertTrue(database.cameraCropFactor("Canon", "Canon EOS 5DS") != null)
            }
        } finally {
            engine.close()
        }
    }
}
