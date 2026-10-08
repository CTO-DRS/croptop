package app.croptop.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.croptop.core.model.FontBytes
import app.croptop.core.model.FontFace
import app.croptop.core.model.FontFamily
import app.croptop.core.model.FontFile
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Where installed fonts come from (the system list on Android; a fixed list in tests). */
fun interface FontSource {
    fun faces(): List<FontFace>
}

/** Fonts for the text tool's picker: installed families, favorites and recently used. */
interface FontRepository {
    /** Scanned once per process, off the main thread. */
    val families: Flow<List<FontFamily>>
    val favorites: Flow<Set<String>>

    /** Most recent first. */
    val recent: Flow<List<String>>

    suspend fun toggleFavorite(family: String)

    suspend fun markUsed(family: String)
}

@Singleton
class DataStoreFontRepository @Inject constructor(
    private val source: FontSource,
    private val store: DataStore<Preferences>,
    private val google: GoogleFonts,
) : FontRepository {
    /** The system's faces, scanned once per process. */
    private val systemFaces: List<FontFace> by lazy { source.faces() }

    // Installed Google families join the system ones; the list re-emits whenever one is installed or removed.
    override val families: Flow<List<FontFamily>> = google.installed.map { FontFamily.group(systemFaces + google.faces()) }.flowOn(Dispatchers.IO)

    override val favorites: Flow<Set<String>> = store.data.map { it[FAVORITES].orEmpty() }

    override val recent: Flow<List<String>> = store.data.map { p -> p[RECENT]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty() }

    override suspend fun toggleFavorite(family: String) {
        store.edit { p ->
            val f = p[FAVORITES].orEmpty()
            p[FAVORITES] = if (family in f) f - family else f + family
        }
    }

    override suspend fun markUsed(family: String) {
        store.edit { p ->
            val list = p[RECENT]?.split('\n')?.filter { it.isNotEmpty() && it != family }.orEmpty()
            p[RECENT] = (listOf(family) + list).take(MAX_RECENT).joinToString("\n")
        }
    }

    private companion object {
        val FAVORITES = stringSetPreferencesKey("fonts.favorites")
        val RECENT = stringPreferencesKey("fonts.recent")
        const val MAX_RECENT = 8
    }
}

/** Installed fonts from [android.graphics.fonts.SystemFonts]; each file is read for its names and axes. */
class SystemFontSource @Inject constructor() : FontSource {
    override fun faces(): List<FontFace> = android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull { font ->
        val file = font.file ?: return@mapNotNull null
        val face = runCatching { readFace(file, font.ttcIndex) }.getOrNull() ?: return@mapNotNull null
        // Variable system fonts (Roboto on Android 12+) are listed once per weight with a wght setting:
        // name those instances by their weight instead of the file's default-instance subfamily.
        val instance = font.axes?.any { it.tag == "wght" || it.tag == "ital" || it.tag == "slnt" } == true
        if (instance) {
            val italic = font.style.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC
            face.copy(style = FontFile.styleName(font.style.weight, italic), weight = font.style.weight, italic = italic)
        } else {
            face
        }
    }

    private fun readFace(file: File, index: Int): FontFace = RandomAccessFile(file, "r").use { raf ->
        val bytes = FontBytes { off, len ->
            val n = (raf.length() - off).coerceIn(0, len.toLong()).toInt()
            ByteArray(n).also {
                raf.seek(off)
                raf.readFully(it)
            }
        }
        FontFile.read(bytes, file.path, index)
    }
}
