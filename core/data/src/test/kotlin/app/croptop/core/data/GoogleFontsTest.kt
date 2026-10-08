package app.croptop.core.data

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class GoogleFontsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val catalogJson = """
        {"axisRegistry":[],"familyMetadataList":[
          {"family":"Lora","category":"Serif","popularity":38,"isOpenSource":true,"fonts":{"400":{},"400i":{},"500":{},"700":{},"700i":{}}},
          {"family":"Pacifico","category":"Handwriting","popularity":135,"isOpenSource":true,"fonts":{"400":{}}},
          {"family":"Roboto","category":"Sans Serif","popularity":2,"isOpenSource":true,"fonts":{"100":{},"300":{},"500":{},"900i":{}}},
          {"family":"Closed Font","category":"Serif","popularity":1,"isOpenSource":false,"fonts":{"400":{}}},
          {"family":"Empty","category":"Serif","popularity":5,"isOpenSource":true,"fonts":{}}
        ],"promotedScript":[]}
    """.trimIndent()

    private val css = """
        @font-face {
          font-family: 'Lora';
          font-style: italic;
          font-weight: 400;
          src: url(https://fonts.gstatic.com/s/lora/v37/a.ttf) format('truetype');
        }
        @font-face {
          font-family: 'Lora';
          font-style: normal;
          font-weight: 700;
          src: url(https://fonts.gstatic.com/s/lora/v37/b.ttf) format('truetype');
        }
    """.trimIndent()

    private val sfnt = byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private class Http(val responses: MutableMap<String, ByteArray>) : GoogleFontsHttp {
        val requests = mutableListOf<String>()
        override fun get(url: String): ByteArray {
            requests += url
            return responses.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw IOException("offline: $url")
        }
    }

    private fun fonts(http: GoogleFontsHttp, now: () -> Long = { 1_000L }) = DiskGoogleFonts(tmp.newFolder(), http, now)

    @Test
    fun `the catalog keeps open families with styles, most popular first`() {
        val list = GoogleFontsParser.catalog(catalogJson)
        assertEquals(listOf("Lora", "Pacifico", "Roboto"), list.map { it.family }.sorted())
        assertEquals(listOf("Roboto", "Lora", "Pacifico"), list.sortedBy { it.popularity }.map { it.family })
    }

    @Test
    fun `a download takes regular and bold with italics, or the first style when there is neither`() {
        val list = GoogleFontsParser.catalog(catalogJson).associateBy { it.family }
        assertEquals(listOf("400", "400i", "700", "700i"), list.getValue("Lora").defaultStyles)
        assertEquals(listOf("400"), list.getValue("Pacifico").defaultStyles)
        assertEquals(listOf("100"), list.getValue("Roboto").defaultStyles)
    }

    @Test
    fun `the css query lists upright before italic and escapes the family`() {
        assertEquals("family=Lora:ital,wght@0,400;0,700;1,400", GoogleFontsParser.cssQuery("Lora", listOf("400i", "700", "400")))
        assertEquals("family=Noto%20Sans%20JP:ital,wght@0,400", GoogleFontsParser.cssQuery("Noto Sans JP", listOf("400")))
        assertEquals(null, GoogleFontsParser.cssQuery("Lora", emptyList()))
    }

    @Test
    fun `css files give weight, slant and url`() {
        assertEquals(
            listOf(GoogleFontFile(400, true, "https://fonts.gstatic.com/s/lora/v37/a.ttf"), GoogleFontFile(700, false, "https://fonts.gstatic.com/s/lora/v37/b.ttf")),
            GoogleFontsParser.cssFiles(css),
        )
    }

    @Test
    fun `install stores the files, reports progress and uninstall removes them`() = runTest {
        val http = Http(
            mutableMapOf(
                "https://fonts.google.com/metadata/fonts" to catalogJson.toByteArray(),
                "https://fonts.googleapis.com/css2?family=Lora" to css.toByteArray(),
                "https://fonts.gstatic.com/" to sfnt,
            ),
        )
        val google = fonts(http)
        val lora = google.catalog().first { it.family == "Lora" }
        val progress = mutableListOf<Float>()
        google.install(lora) { progress += it }
        assertEquals(listOf(0.5f, 1f), progress)
        assertEquals(setOf("Lora"), google.installed.value)
        assertTrue(http.requests.first { it.contains("css2") }.endsWith("family=Lora:ital,wght@0,400;0,700;1,400;1,700"))
        google.uninstall("Lora")
        assertEquals(emptySet(), google.installed.value)
    }

    @Test
    fun `something that is not a font is refused and leaves nothing behind`() = runTest {
        val http = Http(
            mutableMapOf(
                "https://fonts.google.com/metadata/fonts" to catalogJson.toByteArray(),
                "https://fonts.googleapis.com/css2" to css.toByteArray(),
                "https://fonts.gstatic.com/" to "<html>captive portal</html>".toByteArray(),
            ),
        )
        val google = fonts(http)
        assertFailsWith<IOException> { google.install(google.catalog().first { it.family == "Lora" }) }
        assertEquals(emptySet(), google.installed.value)
    }

    @Test
    fun `the catalog is cached for a week and still served when offline`() = runTest {
        val http = Http(mutableMapOf("https://fonts.google.com/metadata/fonts" to catalogJson.toByteArray()))
        var now = System.currentTimeMillis()
        val google = fonts(http) { now }
        assertEquals(3, google.catalog().size)
        google.catalog()
        assertEquals(1, http.requests.size)
        now += 8L * 24 * 60 * 60 * 1000
        http.responses.clear() // offline after the cache expired: the stale copy still serves
        assertEquals(3, google.catalog().size)
        assertEquals(2, http.requests.size) // it did try the network once the week was up
        val fresh = fonts(Http(mutableMapOf()))
        assertFailsWith<IOException> { fresh.catalog() }
    }
}
