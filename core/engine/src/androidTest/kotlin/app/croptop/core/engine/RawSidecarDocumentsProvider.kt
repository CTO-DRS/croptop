package app.croptop.core.engine

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Minimal deterministic SAF tree used to exercise sibling RAW sidecar lookup on Android. */
class RawSidecarDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(
        projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        ),
    ).apply {
        addRow(arrayOf<Any?>("root", "root", "RAW fixture", DocumentsContract.Root.FLAG_SUPPORTS_CREATE, "*/*"))
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documentCursor(projection).apply {
        addDocumentRow(documentId)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        documentCursor(projection).apply {
            if (parentDocumentId == ROOT_ID) {
                addDocumentRow(RAW_ID)
                addDocumentRow(XMP_ID)
            }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && documentId in setOf(RAW_ID, XMP_ID)

    override fun findDocumentPath(rootId: String?, documentId: String): DocumentsContract.Path {
        if (rootId != ROOT_ID || documentId != RAW_ID) throw java.io.FileNotFoundException("Unknown fixture document path")
        return DocumentsContract.Path(ROOT_ID, listOf(ROOT_ID, RAW_ID))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = when (documentId) {
            RAW_ID -> fixtureRawFile()
            XMP_ID -> fixtureXmpFile()
            else -> throw java.io.FileNotFoundException("Unknown fixture document: $documentId")
        } ?: throw java.io.FileNotFoundException("Fixture document has not been registered")
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw IllegalArgumentException("Unsupported fixture mode: $mode")
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != ROOT_ID || mimeType != "application/rdf+xml" || !displayName.endsWith(".xmp", ignoreCase = true)) {
            throw FileNotFoundException("Unsupported fixture document creation")
        }
        val parent = fixtureRawFile()?.parentFile ?: throw FileNotFoundException("Fixture folder is unavailable")
        val created = File(parent, displayName)
        if (!created.exists() && !created.createNewFile()) throw FileNotFoundException("Fixture document could not be created")
        xmpFile = created
        return XMP_ID
    }

    private fun documentCursor(projection: Array<out String>?): MatrixCursor = MatrixCursor(
        projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ),
    )

    private fun MatrixCursor.addDocumentRow(id: String) {
        val file = when (id) {
            ROOT_ID -> null
            RAW_ID -> fixtureRawFile()
            XMP_ID -> fixtureXmpFile()
            else -> null
        }
        val name = when (id) {
            ROOT_ID -> ""
            RAW_ID -> file?.name ?: "saf-sidecar-test.dng"
            XMP_ID -> file?.name ?: "saf-sidecar-test.xmp"
            else -> ""
        }
        val mime = when (id) {
            ROOT_ID -> DocumentsContract.Document.MIME_TYPE_DIR
            RAW_ID -> "image/x-adobe-dng"
            else -> "application/rdf+xml"
        }
        addRow(
            columnNames.map<String, Any?> { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id

                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name

                    DocumentsContract.Document.COLUMN_MIME_TYPE -> mime

                    DocumentsContract.Document.COLUMN_FLAGS -> when {
                        file == null -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                        id == XMP_ID -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        else -> 0
                    }

                    DocumentsContract.Document.COLUMN_SIZE -> file?.length() ?: 0L

                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file?.lastModified() ?: 0L

                    else -> null
                }
            }.toTypedArray(),
        )
    }

    companion object {
        const val ROOT_ID = "root"
        const val RAW_ID = "raw"
        const val XMP_ID = "recipe"

        @Volatile var rawFile: File? = null

        @Volatile var xmpFile: File? = null
    }

    private fun fixtureRawFile(): File? = rawFile?.takeIf(File::isFile)
        ?: context?.cacheDir?.let { cache ->
            listOf("saf-export-test.dng", "saf-sidecar-test.dng")
                .asSequence()
                .map { File(cache, it) }
                .firstOrNull(File::isFile)
        }

    private fun fixtureXmpFile(): File? = xmpFile?.takeIf(File::isFile)
        ?: fixtureRawFile()?.let { raw -> File(raw.parentFile, "${raw.nameWithoutExtension}.xmp") }
            ?.takeIf(File::isFile)
}
