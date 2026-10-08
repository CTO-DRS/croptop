package app.croptop.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecoveryMetadataTest {
    @Test
    fun roundTripsAndToleratesUnknownKeys() {
        val metadata = RecoveryMetadata(
            documentName = "Poster",
            width = 6000,
            height = 4000,
            lastEditEpochMs = 1_790_000_000_000,
            sourceUri = "content://docs/poster.croptop",
            appVersion = "1.0.0",
        )
        assertEquals(metadata, RecoveryMetadata.decodeOrNull(metadata.encode()))
        val future = metadata.encode().decodeToString().replace("{", """{"newField":{"x":1},""")
        assertEquals(metadata, RecoveryMetadata.decodeOrNull(future.encodeToByteArray()))
    }

    @Test
    fun rejectsUnusableMetadataWithoutThrowing() {
        assertNull(RecoveryMetadata.decodeOrNull("{".encodeToByteArray()))
        assertNull(RecoveryMetadata.decodeOrNull("""{"documentName":"x"}""".encodeToByteArray()))
        assertNull(
            RecoveryMetadata.decodeOrNull(
                """{"journalVersion":2,"documentName":"x","width":1,"height":1,"lastEditEpochMs":0}""".encodeToByteArray(),
            ),
        )
        assertNull(
            RecoveryMetadata.decodeOrNull(
                """{"documentName":"x","width":0,"height":1,"lastEditEpochMs":0}""".encodeToByteArray(),
            ),
        )
        assertNull(RecoveryMetadata.decodeOrNull(byteArrayOf(0x7b, 0xff.toByte())))
    }
}
