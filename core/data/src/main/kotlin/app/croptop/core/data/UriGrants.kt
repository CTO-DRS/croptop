package app.croptop.core.data

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted SAF read/write grants (Android allows a bounded number per app). */
interface UriGrants {
    fun release(uri: String)
}

@Singleton
class ContentResolverUriGrants @Inject constructor(@ApplicationContext private val context: Context) : UriGrants {
    override fun release(uri: String) {
        if (!uri.startsWith("content://")) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(uri.toUri(), flags) }
            .recoverCatching { context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
