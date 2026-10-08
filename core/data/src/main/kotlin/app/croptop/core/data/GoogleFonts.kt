package app.croptop.core.data

import android.content.Context
import app.croptop.core.model.FontBytes
import app.croptop.core.model.FontFace
import app.croptop.core.model.FontFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One family of the Google Fonts catalog. [styles] are the catalog's keys: weight, plus `i` for italic (`400`, `700i`). */
data class GoogleFontListing(val family: String, val category: String, val popularity: Int, val styles: List<String>) {
    /** The styles a download fetches: regular and bold with their italics when the family has them, else its first style. */
    val defaultStyles: List<String>
        get() = listOf("400", "400i", "700", "700i").filter { it in styles }.ifEmpty { styles.take(1) }
}

/** One font file named by the CSS API. */
data class GoogleFontFile(val weight: Int, val italic: Boolean, val url: String)

object GoogleFontsParser {
    fun catalog(json: String): List<GoogleFontListing> {
        val families = Json.parseToJsonElement(json).jsonObject["familyMetadataList"]?.jsonArray ?: return emptyList()
        return families.mapNotNull { element ->
            val f = element.jsonObject
            val family = f["family"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (f["isOpenSource"]?.jsonPrimitive?.content == "false") return@mapNotNull null
            val styles = (f["fonts"] as? JsonObject)?.keys?.toList().orEmpty().ifEmpty { return@mapNotNull null }
            GoogleFontListing(family, f["category"]?.jsonPrimitive?.content.orEmpty(), f["popularity"]?.jsonPrimitive?.int ?: Int.MAX_VALUE, styles)
        }
    }

    /** The `@font-face` blocks of a CSS response, each with the TrueType file it points at. */
    fun cssFiles(css: String): List<GoogleFontFile> = FACE.findAll(css).mapNotNull { m ->
        val body = m.groupValues[1]
        val url = URL_RE.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        val weight = WEIGHT.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 400
        GoogleFontFile(weight, italic = STYLE.find(body)?.groupValues?.get(1) == "italic", url = url)
    }.toList()

    /** `family=Lora:ital,wght@0,400;1,400` for [styles] (catalog keys), or null when there are none. */
    fun cssQuery(family: String, styles: List<String>): String? {
        if (styles.isEmpty()) return null
        val entries = styles.map { s -> (if (s.endsWith("i")) 1 else 0) to s.trimEnd('i').toInt() }.sortedWith(compareBy({ it.first }, { it.second }))
        val name = URLEncoder.encode(family, "UTF-8").replace("+", "%20")
        return "family=$name:ital,wght@" + entries.joinToString(";") { "${it.first},${it.second}" }
    }

    private val FACE = Regex("@font-face\\s*\\{([^}]*)\\}")
    private val URL_RE = Regex("src:[^;]*url\\((https://[^)]+\\.ttf)\\)")
    private val WEIGHT = Regex("font-weight:\\s*(\\d+)")
    private val STYLE = Regex("font-style:\\s*(\\w+)")
}

/** Minimal HTTP seam so tests need no network. */
fun interface GoogleFontsHttp {
    @Throws(IOException::class)
    fun get(url: String): ByteArray
}

class UrlGoogleFontsHttp @Inject constructor() : GoogleFontsHttp {
    override fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        // No browser User-Agent: the CSS API then answers with TrueType files, which the text engine reads.
        connection.setRequestProperty("User-Agent", "CropTop")
        try {
            if (connection.responseCode != 200) throw IOException("Google Fonts answered ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

/** Downloadable fonts from Google Fonts (SIL OFL / Apache families): browse the catalog, install a family, remove it again. */
interface GoogleFonts {
    /** Families installed on this device. */
    val installed: StateFlow<Set<String>>

    /** The families of the catalog, popular first. Uses the cached copy for a week and when offline. */
    suspend fun catalog(): List<GoogleFontListing>

    /** Downloads [family]'s default styles; [onProgress] receives 0–1. Throws [IOException] when it cannot. */
    suspend fun install(family: GoogleFontListing, onProgress: (Float) -> Unit = {})

    fun uninstall(family: String)

    /** Faces of every installed family, for the font picker. */
    fun faces(): List<FontFace>
}

@Singleton
class DiskGoogleFonts internal constructor(
    private val root: File,
    private val http: GoogleFontsHttp,
    private val clock: () -> Long,
) : GoogleFonts {
    @Inject
    constructor(@ApplicationContext context: Context, http: UrlGoogleFontsHttp) :
        this(File(context.filesDir, "fonts/google"), http, System::currentTimeMillis)

    private val state = MutableStateFlow(scan())
    override val installed: StateFlow<Set<String>> = state.asStateFlow()

    private val catalogFile get() = File(root, CATALOG)

    override suspend fun catalog(): List<GoogleFontListing> = withContext(Dispatchers.IO) {
        val cached = catalogFile.takeIf { it.isFile }
        if (cached != null && clock() - cached.lastModified() < CATALOG_TTL_MS) {
            runCatching { GoogleFontsParser.catalog(cached.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it.byPopularity() }
        }
        try {
            val text = http.get(CATALOG_URL).decodeToString()
            val parsed = GoogleFontsParser.catalog(text)
            if (parsed.isEmpty()) throw IOException("The Google Fonts catalog was empty")
            root.mkdirs()
            catalogFile.writeText(text)
            parsed.byPopularity()
        } catch (offline: IOException) {
            cached?.let { runCatching { GoogleFontsParser.catalog(it.readText()) }.getOrNull() }?.takeIf { it.isNotEmpty() }?.byPopularity() ?: throw offline
        }
    }

    override suspend fun install(family: GoogleFontListing, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val query = GoogleFontsParser.cssQuery(family.family, family.defaultStyles) ?: throw IOException("${family.family} has no styles")
        val files = GoogleFontsParser.cssFiles(http.get("$CSS_URL?$query").decodeToString())
        if (files.isEmpty()) throw IOException("Google Fonts returned no files for ${family.family}")
        val dir = File(root, safe(family.family))
        val staging = File(root, ".${safe(family.family)}.part").apply { deleteRecursively(); mkdirs() }
        try {
            files.forEachIndexed { i, file ->
                val bytes = http.get(file.url)
                if (bytes.size < 12 || !isSfnt(bytes)) throw IOException("${family.family} came back as something other than a font")
                File(staging, styleFile(file.weight, file.italic)).writeBytes(bytes)
                onProgress((i + 1f) / files.size)
            }
            File(staging, FAMILY_FILE).writeText(family.family)
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) throw IOException("Could not store ${family.family}")
        } finally {
            staging.deleteRecursively()
        }
        state.value = scan()
    }

    override fun uninstall(family: String) {
        File(root, safe(family)).deleteRecursively()
        state.value = scan()
    }

    override fun faces(): List<FontFace> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().flatMap { dir ->
        dir.listFiles { f -> f.extension == "ttf" }.orEmpty().mapNotNull { file ->
            val (weight, italic) = parseStyleFile(file.name) ?: return@mapNotNull null
            runCatching { readFace(file) }.getOrNull()?.copy(style = FontFile.styleName(weight, italic), weight = weight, italic = italic)
        }
    }

    private fun scan(): Set<String> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.listFiles { x -> x.extension == "ttf" }?.isNotEmpty() == true }
        .orEmpty().mapNotNull { dir -> File(dir, FAMILY_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.toSet()

    private fun List<GoogleFontListing>.byPopularity() = sortedBy { it.popularity }

    private fun readFace(file: File): FontFace = RandomAccessFile(file, "r").use { raf ->
        FontFile.read(
            FontBytes { off, len ->
                val n = (raf.length() - off).coerceIn(0, len.toLong()).toInt()
                ByteArray(n).also {
                    raf.seek(off)
                    raf.readFully(it)
                }
            },
            file.path,
            0,
        )
    }

    private fun isSfnt(b: ByteArray) = (b[0].toInt() == 0 && b[1].toInt() == 1 && b[2].toInt() == 0 && b[3].toInt() == 0) || String(b, 0, 4, Charsets.ISO_8859_1) in setOf("true", "OTTO", "ttcf")

    private companion object {
        const val CATALOG = ".catalog.json"
        const val FAMILY_FILE = "family.txt"
        const val CATALOG_URL = "https://fonts.google.com/metadata/fonts"
        const val CSS_URL = "https://fonts.googleapis.com/css2"
        const val CATALOG_TTL_MS = 7L * 24 * 60 * 60 * 1000

        fun safe(family: String) = family.replace(Regex("[^A-Za-z0-9]+"), "_")

        fun styleFile(weight: Int, italic: Boolean) = "$weight${if (italic) "i" else ""}.ttf"

        fun parseStyleFile(name: String): Pair<Int, Boolean>? {
            val stem = name.removeSuffix(".ttf")
            val weight = stem.trimEnd('i').toIntOrNull() ?: return null
            return weight to stem.endsWith("i")
        }
    }
}
