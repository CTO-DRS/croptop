// Metadata the native recovery journal stores verbatim.
package app.croptop.core.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the launch "Recover N documents" sheet needs without loading pixels: it maps onto
 * the `RecoverableDocument(id, name, size, lastEdit, sourceUri?)`.
 */
@Serializable
data class RecoveryMetadata(
    val journalVersion: Int = JOURNAL_VERSION,
    val documentName: String,
    val width: Int,
    val height: Int,
    val lastEditEpochMs: Long,
    /** SAF uri or app file the document was opened from or last saved to, if any. */
    val sourceUri: String? = null,
    val appVersion: String = "",
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        const val JOURNAL_VERSION = 1
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Returns null for metadata this version cannot use, so one bad journal never hides the others. */
        fun decodeOrNull(bytes: ByteArray): RecoveryMetadata? = try {
            json.decodeFromString(serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
                .takeIf { it.journalVersion in 1..JOURNAL_VERSION && it.width > 0 && it.height > 0 }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
