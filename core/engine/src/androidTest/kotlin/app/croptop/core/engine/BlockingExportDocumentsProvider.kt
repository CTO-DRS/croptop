package app.croptop.core.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object BlockingExportPipe {
    @Volatile
    private var opened = CountDownLatch(1)

    @Volatile
    private var reader: ParcelFileDescriptor? = null

    fun reset() {
        reader?.close()
        reader = null
        opened = CountDownLatch(1)
    }

    fun publish(readEnd: ParcelFileDescriptor) {
        reader = readEnd
        opened.countDown()
    }

    fun awaitOpen(timeout: Long, unit: TimeUnit): Boolean = opened.await(timeout, unit)

    fun drain(): ByteArray {
        val descriptor = checkNotNull(reader) { "Export pipe has not opened" }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            ByteArrayOutputStream().also { output -> input.copyTo(output) }.toByteArray()
        }.also { reader = null }
    }
}

class BlockingExportDocumentsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains('w')) throw FileNotFoundException("Export stream must be writable")
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        BlockingExportPipe.publish(readEnd)
        return writeEnd
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
