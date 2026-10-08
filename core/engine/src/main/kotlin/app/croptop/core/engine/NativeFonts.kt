package app.croptop.core.engine

import java.io.File

/**
 * Resolves Character panel families/styles to installed font files (Android's /system/fonts), with a
 * fallback chain for scripts the primary font lacks (CJK, Arabic, Hebrew, Devanagari, Thai).
 */
internal object NativeFonts {
    private val directories = listOf("/system/fonts", "/product/fonts", "/system/font")
    private val fallbacks = listOf(
        "Roboto-Regular.ttf",
        "NotoSansCJK-Regular.ttc",
        "NotoNaskhArabic-Regular.ttf",
        "NotoSansHebrew-Regular.ttf",
        "NotoSansDevanagari-Regular.ttf",
        "NotoSansThai-Regular.ttf",
        "DroidSans.ttf",
        "DejaVuSans.ttf",
    )

    private val files: Map<String, String> by lazy {
        directories.flatMap { dir -> File(dir).listFiles()?.toList().orEmpty() }
            .filter { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true) || it.name.endsWith(".ttc", true)) }
            .associate { it.name.lowercase() to it.absolutePath }
    }

    /** Installed family names, derived from file names ("Roboto-Bold.ttf" → "Roboto"). */
    val families: List<String> by lazy { files.keys.map { it.substringBefore('-').substringBeforeLast('.') }.distinct().sorted() }

    /** Primary file for [family]/[style] first, then the script fallbacks; only existing files. */
    fun resolve(family: String, style: String): List<String> {
        val key = family.replace(" ", "").lowercase()
        val wanted = style.replace(" ", "").lowercase()
        val primary = listOf("$key-$wanted", "$key-regular", key).firstNotNullOfOrNull { stem ->
            listOf(".ttf", ".otf", ".ttc").firstNotNullOfOrNull { ext -> files[stem + ext] }
        } ?: files.entries.firstOrNull { it.key.startsWith(key) }?.value
        return (listOfNotNull(primary) + fallbacks.mapNotNull { files[it.lowercase()] }).distinct()
    }
}
