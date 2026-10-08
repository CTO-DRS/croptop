package app.croptop.core.model

/** A virtual copy of a photo: its own Develop recipe over the same file (Lightroom-style), no extra pixels on disk. */
data class VirtualCopy(val id: Long, val name: String)

/** A named restore point of a photo's Develop recipe (like Lightroom snapshots); applying one is an undoable step. */
data class DevelopSnapshot(val id: Long, val name: String, val createdAt: Long, val settings: DevelopSettings)

/** The key under which Develop stores the recipe of [uri] (the original) or of its virtual copy [copyId]. */
fun developKey(uri: String, copyId: Long?): String = if (copyId == null) uri else "$uri#copy$copyId"
