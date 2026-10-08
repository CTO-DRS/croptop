package app.croptop.core.data.catalog

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import app.croptop.core.model.CatalogImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SupportedExtensions =
    setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "tif", "tiff", "psd", "gif", "bmp") + CatalogImage.RawExtensions

/** Walks a SAF tree with DocumentsContract (no per-file permission prompts). */
class SafFolderSource @Inject constructor(@ApplicationContext private val context: Context) : FolderSource {
    override suspend fun list(treeUri: String): List<ScannedFile> = withContext(Dispatchers.IO) {
        val tree = treeUri.toUri()
        val out = mutableListOf<ScannedFile>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty()) {
            val children: Uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) pending += id
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (!mime.startsWith("image/") && ext !in SupportedExtensions) continue
                    out += ScannedFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, mime, c.getLong(3), c.getLong(4))
                }
            }
        }
        out
    }
}

/** EXIF via androidx.exifinterface (JPEG, HEIF, PNG, WebP, DNG and most RAWs' TIFF headers). */
class ExifMetadataReader @Inject constructor(@ApplicationContext private val context: Context) : MetadataReader {
    private val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    override suspend fun read(uri: String): ImageMetadata? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri.toUri())?.use { stream ->
            val exif = ExifInterface(stream)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            val camera = listOfNotNull(make, model?.removePrefix(make ?: "")?.trim()).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
            val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                if (it >= 1) "${"%.1f".format(it).trimEnd('0').trimEnd('.')} s" else "1/${(1 / it).toInt()} s"
            }
            ImageMetadata(
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                takenAt = date?.let { runCatching { exifDate.parse(it)?.time }.getOrNull() },
                camera = camera,
                lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifBlank { null },
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                exposure = exposure,
                aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.toFloat(),
                focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.toFloat(),
                caption = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.trim()?.ifBlank { null },
                creator = exif.getAttribute(ExifInterface.TAG_ARTIST)?.trim()?.ifBlank { null },
                copyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT)?.trim()?.ifBlank { null },
            )
        }
    }
}
