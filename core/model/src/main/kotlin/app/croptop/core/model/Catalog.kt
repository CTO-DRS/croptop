package app.croptop.core.model

import kotlinx.serialization.Serializable

/** Pick/reject flag. */
@Serializable
enum class Flag { NONE, PICK, REJECT }

/** Color labels (keys 6–9 in the Library). */
@Serializable
enum class ColorLabel(val title: String, val argb: Long) {
    NONE("None", 0x00000000),
    RED("Red", 0xFFE5484D),
    YELLOW("Yellow", 0xFFF5C518),
    GREEN("Green", 0xFF30A46C),
    BLUE("Blue", 0xFF3E7BFA),
}

/** A photo in the library catalog. */
@Serializable
data class CatalogImage(
    val id: Long,
    val uri: String,
    val folderUri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val takenAt: Long? = null,
    val camera: String? = null,
    val lens: String? = null,
    val iso: Int? = null,
    val exposure: String? = null,
    val aperture: Float? = null,
    val focalLength: Float? = null,
    val rating: Int = 0,
    val flag: Flag = Flag.NONE,
    val label: ColorLabel = ColorLabel.NONE,
    val title: String? = null,
    val caption: String? = null,
    val creator: String? = null,
    val copyright: String? = null,
) {
    val isRaw: Boolean get() = isRawName(name)

    companion object {
        fun isRawName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in RawExtensions

        val RawExtensions =
            setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "srw", "x3f", "3fr", "iiq", "rwl")
    }
}

@Serializable
data class LibraryFolder(val treeUri: String, val name: String, val imageCount: Int = 0)

/** Library filter bar. */
@Serializable
data class LibraryFilter(
    val minRating: Int = 0,
    val flag: Flag? = null,
    val label: ColorLabel? = null,
    val query: String = "",
    val folderUri: String? = null,
    val rawOnly: Boolean = false,
    /** Show only this collection's photos (manual collections). */
    val collectionId: Long? = null,
    val sort: LibrarySort = LibrarySort.CAPTURE_NEWEST,
) {
    /** True when something narrows the photos (the source and the sort order don't count). */
    val isActive: Boolean get() = copy(folderUri = null, collectionId = null, sort = LibrarySort.CAPTURE_NEWEST) != LibraryFilter()

    /** The criteria a smart collection saves: everything except where you're browsing and the sort. */
    fun criteria(): LibraryFilter = copy(folderUri = null, collectionId = null, sort = LibrarySort.CAPTURE_NEWEST)
}

@Serializable
enum class LibrarySort(val label: String) {
    CAPTURE_NEWEST("Newest first"),
    CAPTURE_OLDEST("Oldest first"),
    NAME("File name"),
    RATING("Rating"),
    ADDED("Recently added"),
}

/**
 * A Library collection. Manual collections hold photos you add; smart collections
 * are a saved [LibraryFilter] and update themselves. [imageCount] is null for smart collections.
 */
data class LibraryCollection(val id: Long, val name: String, val smart: LibraryFilter? = null, val imageCount: Int? = null) {
    val isSmart: Boolean get() = smart != null
}

/** Library ▸ IPTC batch edit: null = leave unchanged, blank = clear. */
data class IptcEdit(val title: String? = null, val caption: String? = null, val creator: String? = null, val copyright: String? = null)
