package app.croptop.core.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeReplaceTest {
    private val work: File = Files.createTempDirectory("safe-replace").toFile()

    /** A destination like a document provider: writes land in [content] as they are made. */
    private class Destination(var content: ByteArray, val failAfter: Int = -1, val failOnRestore: Boolean = false) {
        var opens = 0
        fun open(): OutputStream {
            opens++
            val restoring = opens > 1
            content = ByteArray(0)
            return object : OutputStream() {
                val buffer = ByteArrayOutputStream()
                override fun write(b: Int) {
                    if ((!restoring && failAfter >= 0 && buffer.size() >= failAfter) || (restoring && failOnRestore)) throw IOException("disk full")
                    buffer.write(b)
                    content = buffer.toByteArray()
                }
            }
        }
    }

    @Test
    fun `a successful save replaces the file and leaves no staging files behind`() {
        val dest = Destination("old".toByteArray())
        val n = SafeReplace.replace(work, "a.png", { it.write("new contents".toByteArray()); 12 }, { ByteArrayInputStream(dest.content) }, dest::open)
        assertEquals(12, n)
        assertContentEquals("new contents".toByteArray(), dest.content)
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an encoder failure never touches the destination`() {
        val dest = Destination("old".toByteArray())
        assertFailsWith<IllegalStateException> {
            SafeReplace.replace(work, "a.png", { it.write(1); error("encoder crashed") }, { ByteArrayInputStream(dest.content) }, dest::open)
        }
        assertEquals(0, dest.opens)
        assertContentEquals("old".toByteArray(), dest.content)
        assertFailsWith<IOException> { SafeReplace.replace(work, "a.png", { 0 }, { ByteArrayInputStream(dest.content) }, dest::open) }
        assertContentEquals("old".toByteArray(), dest.content)
    }

    @Test
    fun `a write that fails part way puts the old bytes back`() {
        val dest = Destination("the original file".toByteArray(), failAfter = 3)
        assertFailsWith<IOException> {
            SafeReplace.replace(work, "a.png", { it.write("a much longer new file".toByteArray()); 22 }, { ByteArrayInputStream(dest.content) }, dest::open)
        }
        assertContentEquals("the original file".toByteArray(), dest.content)
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `when the old bytes cannot be put back a copy is kept and named in the error`() {
        val dest = Destination("the original file".toByteArray(), failAfter = 3, failOnRestore = true)
        val failure = assertFailsWith<SafeReplace.RestoreFailed> {
            SafeReplace.replace(work, "a.png", { it.write("new file".toByteArray()); 8 }, { ByteArrayInputStream(dest.content) }, dest::open)
        }
        assertContentEquals("the original file".toByteArray(), failure.backup.readBytes())
        assertTrue(failure.message!!.contains(failure.backup.path))
    }
}
