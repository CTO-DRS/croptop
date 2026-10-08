package app.croptop.core.io

/** Raster formats recognized by the import pipeline. Detection always uses content signatures. */
enum class ImageFormat(val mimeType: String, val extensions: Set<String>) {
    JPEG("image/jpeg", setOf("jpg", "jpeg")),
    PNG("image/png", setOf("png")),
    WEBP("image/webp", setOf("webp")),
    GIF("image/gif", setOf("gif")),
    BMP("image/bmp", setOf("bmp")),
    HEIF("image/heif", setOf("heic", "heif")),
    AVIF("image/avif", setOf("avif")),
    UNKNOWN("application/octet-stream", emptySet()),
    ;

    companion object {
        /** Detects JPEG, PNG, WebP, GIF, BMP and ISO-BMFF HEIF/AVIF without trusting a filename. */
        fun detect(header: ByteArray, length: Int = header.size): ImageFormat {
            require(length in 0..header.size) { "length must be within the supplied header" }
            if (length >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) {
                return JPEG
            }
            if (length >= PNG_SIGNATURE.size && header.startsWith(PNG_SIGNATURE)) return PNG
            if (length >= 12 && header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WEBP") return WEBP
            if (length >= 6 && (header.ascii(0, 6) == "GIF87a" || header.ascii(0, 6) == "GIF89a")) return GIF
            if (length >= 2 && header.ascii(0, 2) == "BM") return BMP
            if (length >= 12 && header.ascii(4, 4) == "ftyp") {
                val brands = buildList {
                    add(header.ascii(8, 4))
                    var offset = 16
                    while (offset + 4 <= length) {
                        add(header.ascii(offset, 4))
                        offset += 4
                    }
                }
                if (brands.any { it in AVIF_BRANDS }) return AVIF
                if (brands.any { it in HEIF_BRANDS }) return HEIF
            }
            return UNKNOWN
        }

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
        private val AVIF_BRANDS = setOf("avif", "avis")

        private fun ByteArray.ascii(offset: Int, size: Int): String =
            String(this, offset, size, Charsets.US_ASCII)

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            prefix.indices.all { this[it] == prefix[it] }
    }
}
