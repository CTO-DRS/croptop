package app.croptop.core.ai

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class ModelStoreTest {
    private val root: File = Files.createTempDirectory("model-store-test").toFile()
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val small = ByteArray(1_000) { (it % 7).toByte() }
    private val model = spec(
        files = listOf(
            ModelFile("weights.tflite", sha256Hex(payload), payload.size.toLong(), "https://models.example/weights"),
            ModelFile("labels.txt", sha256Hex(small), small.size.toLong(), "https://models.example/labels"),
        ),
    )
    private val data = mapOf("https://models.example/weights" to payload, "https://models.example/labels" to small)

    /** Serves [data]; [honourResume] false ignores the offset like a server without range support. */
    private inner class FakeServer(var honourResume: Boolean = true, val failAfter: Int = Int.MAX_VALUE) : Downloader {
        val offsets = mutableListOf<Long>()
        override fun open(url: String, resumeFrom: Long): DownloadStream {
            offsets += resumeFrom
            val bytes = data[url] ?: throw IOException("404")
            val start = if (honourResume) resumeFrom.toInt() else 0
            val body = bytes.copyOfRange(start, bytes.size)
            val stream = if (failAfter < body.size) {
                object : java.io.InputStream() {
                    private var at = 0
                    override fun read(): Int = if (at++ < failAfter) body[at - 1].toInt() and 0xff else throw IOException("connection reset")
                }
            } else {
                ByteArrayInputStream(body)
            }
            return DownloadStream(stream, resumed = honourResume && resumeFrom > 0)
        }
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun installsVerifiesAndReportsMonotoneProgress() {
        val store = ModelStore(root, FakeServer())
        assertEquals(ModelState.NotInstalled, store.state(model))
        val progress = mutableListOf<Long>()
        val result = store.install(model, onProgress = { done, total ->
            assertEquals(model.totalBytes, total)
            progress += done
        })
        assertEquals(InstallResult.Installed, result)
        assertEquals(ModelState.Installed, store.state(model))
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "progress went backwards")
        assertEquals(model.totalBytes, progress.last())
        assertNotNull(store.file(model, "weights.tflite"))
        assertEquals(model.totalBytes, store.usedBytes())
        assertNull(store.file(model, "missing.bin"))
    }

    @Test
    fun aSecondInstallDownloadsNothing() {
        val server = FakeServer()
        val store = ModelStore(root, server)
        store.install(model)
        val requests = server.offsets.size
        assertEquals(InstallResult.Installed, store.install(model))
        assertEquals(requests, server.offsets.size)
    }

    @Test
    fun aChecksumMismatchNeverInstalls() {
        val corrupted = data.toMutableMap().also { it["https://models.example/weights"] = payload.copyOf().also { b -> b[10] = 0x55 } }
        val downloader = Downloader { url, _ -> DownloadStream(ByteArrayInputStream(corrupted.getValue(url)), false) }
        val store = ModelStore(root, downloader)
        val result = store.install(model)
        assertIs<InstallResult.Failed>(result)
        assertTrue("checksum" in result.reason)
        assertFalse(File(store.directory(model), "weights.tflite").exists())
        assertFalse(File(store.directory(model), "weights.tflite.part").exists())
        assertEquals(ModelState.NotInstalled, store.state(model))
    }

    @Test
    fun aWrongSizeIsRejected() {
        val truncated = Downloader { url, _ -> DownloadStream(ByteArrayInputStream(data.getValue(url).copyOf(100)), false) }
        val result = ModelStore(root, truncated).install(model)
        assertIs<InstallResult.Failed>(result)
        assertTrue("expected" in result.reason)
    }

    @Test
    fun cancellingKeepsThePartialFileAndResumeContinuesFromIt() {
        val server = FakeServer()
        val store = ModelStore(root, server)
        var polls = 0
        val cancelled = store.install(model, cancelled = { ++polls > 3 })
        assertEquals(InstallResult.Cancelled, cancelled)
        val state = store.state(model)
        assertIs<ModelState.Partial>(state)
        assertTrue(state.bytesOnDisk in 1 until model.totalBytes)

        server.offsets.clear()
        assertEquals(InstallResult.Installed, store.install(model))
        assertTrue(server.offsets.first() > 0L, "the second install should resume, not restart")
        assertEquals(ModelState.Installed, store.state(model))
    }

    @Test
    fun aServerWithoutRangeSupportRestartsCleanly() {
        val server = FakeServer()
        val store = ModelStore(root, server)
        var polls = 0
        store.install(model, cancelled = { ++polls > 2 })
        server.honourResume = false
        assertEquals(InstallResult.Installed, store.install(model))
        assertEquals(ModelState.Installed, store.state(model))
        assertEquals(model.totalBytes, store.usedBytes())
    }

    @Test
    fun aDroppedConnectionFailsWithoutLeavingAnInstalledLookalike() {
        val result = ModelStore(root, FakeServer(failAfter = 5_000)).install(model)
        assertIs<InstallResult.Failed>(result)
        assertNotEquals(ModelState.Installed, ModelStore(root, FakeServer()).state(model))
    }

    @Test
    fun deleteAndPruneFreeTheSpace() {
        val store = ModelStore(root, FakeServer())
        val v1 = model
        val v2 = model.copy(version = 2)
        val other = model.copy(id = "other-model")
        store.install(v1)
        store.install(v2)
        store.install(other)
        store.prune(mapOf("u2net-small" to 2))
        assertEquals(ModelState.NotInstalled, store.state(v1))
        assertEquals(ModelState.Installed, store.state(v2))
        assertEquals(ModelState.NotInstalled, store.state(other))
        assertFalse(File(root, "other-model").exists())
        store.delete(v2)
        assertEquals(0L, store.usedBytes())
        assertFalse(File(root, "u2net-small").exists())
    }

    @Test
    fun anInvalidSpecIsRefusedBeforeAnyDownload() {
        val server = FakeServer()
        val result = ModelStore(root, server).install(model.copy(license = ModelLicense("CC-BY-NC-4.0", commercialUse = false)))
        assertIs<InstallResult.Failed>(result)
        assertTrue(server.offsets.isEmpty())
    }

    private fun <T> assertNotEquals(unexpected: T, actual: T) = assertTrue(unexpected != actual, "expected a value other than $unexpected")
}
