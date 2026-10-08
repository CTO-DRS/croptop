package app.croptop.app.files

import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext

/** Everything CropTop can open. Unknown RAW types arrive as octet-stream. */
val OpenableMimeTypes = arrayOf(
    "image/*",
    "image/x-adobe-dng",
    "image/x-canon-cr2",
    "image/x-canon-cr3",
    "image/x-nikon-nef",
    "image/x-sony-arw",
    "image/x-fuji-raf",
    "image/vnd.adobe.photoshop",
    "application/x-croptop",
    "application/octet-stream",
)

/**
 * System file picker (SAF). Read permission is persisted so recents and autosave survive restarts.
 * Returns a launcher; call it with no arguments.
 */
@Composable
fun rememberOpenImageLauncher(onOpened: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onOpened(uri)
        }
    }
    return { launcher.launch(OpenableMimeTypes) }
}

/**
 * Accepts images dragged from Files, a browser or another window anywhere on [this].
 * Drop permissions are requested from the host activity so the content:// URI stays readable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.imageDropTarget(onDropped: (Uri) -> Unit): Modifier {
    val activity = LocalActivity.current
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                activity?.requestDragAndDropPermissions(drag)
                val clip = drag.clipData ?: return false
                val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return false
                onDropped(uri)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { e ->
            e.mimeTypes().any { it.startsWith("image/") || it == ClipDescription.MIMETYPE_TEXT_URILIST || it == "application/octet-stream" }
        },
        target = target,
    )
}

/** First image URI on the system clipboard, if any (Ctrl+V on Home). */
fun clipboardImageUri(context: android.content.Context): Uri? {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    val desc = clip.description
    val looksLikeImage = (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }
    if (!looksLikeImage) return null
    return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
}
