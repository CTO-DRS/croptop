package app.croptop.feature.editor.files

import java.io.File

/**
 * App-private scratch files that can be handed to other apps (share sheet, clipboard, drag-out).
 * The app module implements it with a FileProvider; tests use a temp directory.
 */
interface ShareCache {
    /** A fresh file named [name] in the shareable cache (old share files are pruned). */
    fun newFile(name: String): File

    /** content:// URI other apps can read (read grant added by the caller's intent/clip). */
    fun uriFor(file: File): String
}
