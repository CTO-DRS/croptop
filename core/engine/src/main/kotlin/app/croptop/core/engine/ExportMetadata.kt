package app.croptop.core.engine

import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.IptcMetadata
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Descriptive metadata for exports: IPTC-IIM in a JPEG's Photoshop resource block (APP13) and an XMP packet (JPEG APP1,
 * PNG iTXt). The text goes in as UTF-8 and the IIM records respect their length limits; XMP always carries the full text.
 */
internal object ExportMetadata {
    private const val PHOTOSHOP = "Photoshop 3.0\u0000"
    private const val XMP_JPEG = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val MAX_SEGMENT = 65_533

    /** Bytes to splice in after a JPEG's SOI/JFIF, or null when there is nothing to write. */
    fun jpegSegments(m: IptcMetadata): ByteArray? {
        if (m.isEmpty) return null
        val out = ByteArrayOutputStream()
        val iim = iim(m)
        val photoshop = ByteArrayOutputStream().apply {
            write(PHOTOSHOP.toByteArray(Charsets.ISO_8859_1))
            write("8BIM".toByteArray(Charsets.ISO_8859_1))
            write(0x04)
            write(0x04)
            write(0)
            write(0) // empty Pascal name, padded to even
            writeInt(iim.size)
            write(iim)
            if (iim.size % 2 == 1) write(0)
        }.toByteArray()
        if (photoshop.size <= MAX_SEGMENT) segment(out, 0xED, photoshop)
        val xmp = (XMP_JPEG).toByteArray(Charsets.ISO_8859_1) + xmp(m)
        if (xmp.size <= MAX_SEGMENT) segment(out, 0xE1, xmp)
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** An `iTXt` chunk holding the XMP packet, or null when there is nothing to write. */
    fun pngChunk(m: IptcMetadata): ByteArray? {
        if (m.isEmpty) return null
        val data = ByteArrayOutputStream().apply {
            write("XML:com.adobe.xmp".toByteArray(Charsets.ISO_8859_1))
            write(0) // keyword end
            write(0) // not compressed
            write(0) // compression method
            write(0) // empty language tag
            write(0) // empty translated keyword
            write(xmp(m))
        }.toByteArray()
        val type = "iTXt".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }.value
        return ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(type)
            write(data)
            writeInt(crc.toInt())
        }.toByteArray()
    }

    /** IPTC-IIM records: charset UTF-8, object name, keywords, byline, copyright, caption. */
    fun iim(m: IptcMetadata): ByteArray {
        val out = ByteArrayOutputStream()
        fun record(dataset: Int, value: String, max: Int) {
            val bytes = truncateUtf8(value.trim(), max)
            if (bytes.isEmpty()) return
            out.write(0x1C)
            out.write(2)
            out.write(dataset)
            out.write(bytes.size shr 8)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        // 1:90 coded character set = ESC % G (UTF-8)
        out.write(byteArrayOf(0x1C, 1, 90, 0, 3, 0x1B, 0x25, 0x47))
        m.title?.let { record(5, it, 64) }
        m.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(200).forEach { record(25, it, 64) }
        m.creator?.let { record(80, it, 32) }
        m.copyright?.let { record(116, it, 128) }
        m.caption?.let { record(120, it, 2000) }
        return out.toByteArray()
    }

    fun xmp(m: IptcMetadata): ByteArray {
        fun esc(s: String) = s.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun alt(tag: String, v: String?) = v?.takeIf { it.isNotBlank() }?.let {
            "<dc:$tag><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(it)}</rdf:li></rdf:Alt></dc:$tag>"
        }.orEmpty()
        val keywords = m.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val xml = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
            append(alt("title", m.title))
            append(alt("description", m.caption))
            m.creator?.takeIf { it.isNotBlank() }?.let { append("<dc:creator><rdf:Seq><rdf:li>${esc(it)}</rdf:li></rdf:Seq></dc:creator>") }
            append(alt("rights", m.copyright))
            if (keywords.isNotEmpty()) append("<dc:subject><rdf:Bag>${keywords.joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" }}</rdf:Bag></dc:subject>")
            append("</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
        }
        return xml.toByteArray(Charsets.UTF_8)
    }

    private fun truncateUtf8(s: String, max: Int): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= max) return bytes
        var end = max
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end-- // never cut inside a character
        return bytes.copyOf(end)
    }

    private fun segment(out: ByteArrayOutputStream, marker: Int, payload: ByteArray) {
        out.write(0xFF)
        out.write(marker)
        out.write((payload.size + 2) shr 8)
        out.write((payload.size + 2) and 0xFF)
        out.write(payload)
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write(v ushr 24)
        write(v ushr 16)
        write(v ushr 8)
        write(v)
    }

    /** The bare XMP packet, for WebP's `XMP ` chunk. */
    fun webpXmp(m: IptcMetadata): ByteArray? = if (m.isEmpty) null else xmp(m)

    /** Whether [format] can carry the metadata this object writes. */
    fun supports(format: ExportFormat) = format == ExportFormat.JPEG || format == ExportFormat.PNG || format == ExportFormat.WEBP
}

/**
 * Passes an encoder's output through and splices [extra] in at the one place the container allows: JPEG after SOI and an optional
 * JFIF APP0, PNG after IHDR. Everything else is untouched; [injectedBytes] says how much was added.
 */
internal class MetadataInjectingStream(private val out: OutputStream, private val format: ExportFormat, private val extra: ByteArray) : OutputStream() {
    private val head = ByteArrayOutputStream()
    private var decided = false
    var injectedBytes = 0L
        private set

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (decided) {
            out.write(b, off, len)
            return
        }
        head.write(b, off, len)
        val bytes = head.toByteArray()
        val at = insertionPoint(bytes) ?: return
        decide(bytes, at)
    }

    /** The offset to splice at once [bytes] holds enough to tell, or null while more is needed. */
    private fun insertionPoint(bytes: ByteArray): Int? = when (format) {
        ExportFormat.PNG -> if (bytes.size >= PNG_AFTER_IHDR) PNG_AFTER_IHDR else null

        else -> when {
            bytes.size < 4 -> null
            bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() -> 0 // not a JPEG: leave it alone
            bytes[2] == 0xFF.toByte() && bytes[3] == 0xE0.toByte() -> {
                if (bytes.size < 6) {
                    null
                } else {
                    val end = 2 + 2 + (((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF))
                    if (bytes.size >= end) end else null
                }
            }

            else -> 2
        }
    }

    private fun decide(bytes: ByteArray, at: Int) {
        decided = true
        if (at > 0) {
            out.write(bytes, 0, at)
            out.write(extra)
            injectedBytes += extra.size
            out.write(bytes, at, bytes.size - at)
        } else {
            out.write(bytes)
        }
    }

    override fun flush() {
        out.flush()
    }

    /** Writes anything still held back (an output shorter than the header it waited for). */
    fun finish() {
        if (!decided) {
            decided = true
            out.write(head.toByteArray())
        }
        out.flush()
    }

    private companion object {
        const val PNG_AFTER_IHDR = 8 + 4 + 4 + 13 + 4
    }
}


/**
 * WebP keeps its sizes up front, so the encoder's whole output is held and rewritten on [finish] with an `XMP ` chunk at the end of
 * the RIFF container. A plain file (VP8/VP8L) is first given the extended `VP8X` header that the XMP flag lives in; one that
 * already has it (ICC profile or EXIF present) just gets the flag set.
 */
internal class WebpXmpStream(private val out: OutputStream, private val xmp: ByteArray, private val width: Int, private val height: Int) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    var injectedBytes = 0L
        private set

    override fun write(b: Int) = buffer.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

    fun finish() {
        val source = buffer.toByteArray()
        val rewritten = runCatching { embed(source) }.getOrNull() ?: source // anything unexpected is left as the encoder wrote it
        injectedBytes = (rewritten.size - source.size).toLong()
        out.write(rewritten)
        out.flush()
    }

    override fun flush() = Unit

    private fun embed(src: ByteArray): ByteArray {
        require(src.size >= 20 && String(src, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(src, 8, 4, Charsets.ISO_8859_1) == "WEBP")
        val body = src.copyOfRange(12, src.size)
        val first = String(body, 0, 4, Charsets.ISO_8859_1)
        val chunks = ByteArrayOutputStream()
        if (first == "VP8X") {
            val flagged = body.copyOf()
            flagged[8] = (flagged[8].toInt() or XMP_FLAG).toByte()
            chunks.write(flagged)
        } else {
            require(first == "VP8 " || first == "VP8L") { "unexpected WebP layout" }
            chunks.write("VP8X".toByteArray(Charsets.ISO_8859_1))
            writeLe(chunks, 10, 4)
            chunks.write(XMP_FLAG)
            chunks.write(byteArrayOf(0, 0, 0))
            writeLe(chunks, width - 1, 3)
            writeLe(chunks, height - 1, 3)
            chunks.write(body)
        }
        chunks.write("XMP ".toByteArray(Charsets.ISO_8859_1))
        writeLe(chunks, xmp.size, 4)
        chunks.write(xmp)
        if (xmp.size % 2 == 1) chunks.write(0)
        val all = chunks.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.ISO_8859_1))
            writeLe(this, all.size + 4, 4)
            write("WEBP".toByteArray(Charsets.ISO_8859_1))
            write(all)
        }.toByteArray()
    }

    private fun writeLe(stream: ByteArrayOutputStream, value: Int, bytes: Int) {
        for (i in 0 until bytes) stream.write((value ushr (8 * i)) and 0xFF)
    }

    private companion object {
        const val XMP_FLAG = 0x04
    }
}
