package app.croptop.core.data

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The user-facing file name for a content URI ("IMG_2041.jpg", not "image:27"). */
fun interface DocumentNames {
    fun displayName(uri: String): String?
}

class ContentResolverDocumentNames @Inject constructor(@param:ApplicationContext private val context: Context) : DocumentNames {
    override fun displayName(uri: String): String? = runCatching {
        context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}
