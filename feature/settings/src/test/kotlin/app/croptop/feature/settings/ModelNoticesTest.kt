package app.croptop.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelNoticesTest {
    @Test
    fun `every shipped model is listed with its license, attribution and a license text`() {
        val manifest = listOf(File("../../app/src/main/assets/ai/models.json"), File("app/src/main/assets/ai/models.json")).first { it.exists() }
        val notices = parseModelNotices(manifest.readText())
        assertTrue(notices.size >= 9, "one entry per model")
        notices.forEach { n ->
            assertTrue(n.license.isNotBlank(), "${n.name} has a license")
            assertTrue(n.notes.isNotBlank(), "${n.name} carries its attribution")
            assertTrue(licenseAssetsFor(n).isNotEmpty(), "${n.name} has a license text")
        }
        val texts = listOf(File("../../app/licenses/jvm"), File("app/licenses/jvm")).first { it.exists() }
        notices.flatMap(::licenseAssetsFor).toSet().forEach { assertTrue(File(texts, it).exists(), "$it is bundled") }
    }

    @Test
    fun `the app itself is GPL with its text bundled`() {
        assertEquals("gpl-3.0.txt", AppSelfLicense.textAsset)
        assertTrue(AppSelfLicense.license.contains("General Public License"))
        assertTrue(parseModelNotices("not json").isEmpty())
    }
}
