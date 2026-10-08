package app.croptop.core.ai

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ModelManagerTest {
    private val root = Files.createTempDirectory("model-manager-test").toFile()
    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val spec = ModelSpec(
        id = "seg",
        version = 1,
        task = ModelTask.SEGMENT_SUBJECT,
        displayName = "Subject",
        files = listOf(ModelFile("w.tflite", sha, payload.size.toLong(), "https://m.example/w")),
        license = ModelLicense("Apache-2.0", commercialUse = true),
    )
    private val registry = ModelRegistry.of(listOf(spec))
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun manager(downloader: Downloader) = ModelManager(ModelStore(root, downloader), registry, scope, Dispatchers.Unconfined)

    @Test
    fun installPublishesInstalledAndStorageUsed() {
        val m = manager { _, _ -> DownloadStream(ByteArrayInputStream(payload), false) }
        assertEquals(ModelRow.Status.NotInstalled, m.state.value.rows.single().status)
        m.install("seg")
        assertEquals(ModelRow.Status.Installed, m.state.value.rows.single().status)
        assertEquals(payload.size.toLong(), m.state.value.usedBytes)
        assertTrue(m.isInstalled("seg"))
    }

    @Test
    fun failureIsReportedAndRetryable() {
        var fail = true
        val m = manager { _, _ ->
            if (fail) throw IOException("offline")
            DownloadStream(ByteArrayInputStream(payload), false)
        }
        m.install("seg")
        assertIs<ModelRow.Status.Failed>(m.state.value.rows.single().status)
        fail = false
        m.install("seg")
        assertEquals(ModelRow.Status.Installed, m.state.value.rows.single().status)
    }

    @Test
    fun deleteRemovesFilesAndResetsState() {
        val m = manager { _, _ -> DownloadStream(ByteArrayInputStream(payload), false) }
        m.install("seg")
        m.delete("seg")
        assertEquals(ModelRow.Status.NotInstalled, m.state.value.rows.single().status)
        assertEquals(0L, m.state.value.usedBytes)
    }

    @Test
    fun cancelKeepsPartialForResume() {
        lateinit var m: ModelManager
        val stream = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ == 0) {
                    m.cancel("seg")
                    System.arraycopy(payload, 0, b, off, 1000)
                    return 1000
                }
                return -1
            }
        }
        m = manager { _, _ -> DownloadStream(stream, false) }
        m.install("seg")
        val status = m.state.value.rows.single().status
        assertIs<ModelRow.Status.Partial>(status)
        assertEquals(1000L, status.bytesOnDisk)
    }

    @Test
    fun formatsSizes() {
        assertEquals("512 B", formatModelBytes(512))
        assertEquals("5 MB", formatModelBytes(5L shl 20))
    }
}
