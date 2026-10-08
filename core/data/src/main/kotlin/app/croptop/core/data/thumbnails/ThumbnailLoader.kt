package app.croptop.core.data.thumbnails

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Library thumbnails: system thumbnail provider first, then the EXIF-embedded preview
 * (fast path for RAW files), cached in memory. Replaced by engine thumbnails when lands.
 */
object ThumbnailLoader {
    private const val MAX_BYTES = 96 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    // Keep decode concurrency modest so scrolling a big grid stays smooth.
    private val gate = Semaphore(4)

    fun cached(uri: String, px: Int): Bitmap? = cache.get("$uri@$px")

    suspend fun load(context: Context, uri: String, px: Int): Bitmap? {
        cached(uri, px)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val bmp = runCatching { context.contentResolver.loadThumbnail(uri.toUri(), Size(px, px), null) }.getOrNull()
                    ?: embeddedPreview(context, uri, px)
                bmp?.also { cache.put("$uri@$px", it) }
            }
        }
    }

    private fun embeddedPreview(context: Context, uri: String, px: Int): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri.toUri())?.use { s ->
            val exif = ExifInterface(s)
            exif.thumbnailBytes?.let { bytes ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        }
    }.getOrNull()?.let {
        if (it.width >
            px * 2
        ) {
            it.scale(px, (px * it.height / it.width.toFloat()).toInt().coerceAtLeast(1))
        } else {
            it
        }
    }
}
