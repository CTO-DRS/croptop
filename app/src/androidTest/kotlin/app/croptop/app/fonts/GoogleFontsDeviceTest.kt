package app.croptop.app.fonts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.data.DiskGoogleFonts
import app.croptop.core.data.UrlGoogleFontsHttp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Downloads a real family from Google Fonts and checks the files load as fonts the picker and text engine can use. */
@RunWith(AndroidJUnit4::class)
class GoogleFontsDeviceTest {
    @Test
    fun catalogDownloadAndFacesWorkAgainstTheRealService() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "google-fonts-test").apply { deleteRecursively() }
        val google = DiskGoogleFonts(context, UrlGoogleFontsHttp())
        // The production constructor stores under filesDir; clean any earlier run first.
        File(context.filesDir, "fonts/google").deleteRecursively()
        val catalog = google.catalog()
        assertTrue("the catalog has ${catalog.size} families", catalog.size > 1500)
        assertTrue(catalog.any { it.family == "Roboto" })
        val lora = catalog.first { it.family == "Lora" }
        val progress = mutableListOf<Float>()
        google.install(lora) { progress += it }
        assertEquals(1f, progress.last())
        assertEquals(setOf("Lora"), google.installed.value)
        val faces = google.faces()
        println("[fonts] Lora faces: ${faces.map { "${it.family} ${it.style} w${it.weight} i${it.italic} ${File(it.path).length()}B" }}")
        assertEquals(4, faces.size)
        assertTrue(faces.all { it.family == "Lora" && File(it.path).length() > 20_000 })
        assertTrue(faces.any { it.italic } && faces.any { it.weight >= 700 })
        // The system's Typeface API accepts the file, so the picker can show it in its own face.
        assertTrue(faces.all { android.graphics.Typeface.Builder(File(it.path)).build() != null })
        google.uninstall("Lora")
        assertTrue(google.installed.value.isEmpty())
        root.deleteRecursively()
        Unit
    }
}
