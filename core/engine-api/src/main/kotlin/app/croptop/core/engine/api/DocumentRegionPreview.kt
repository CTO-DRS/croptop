package app.croptop.core.engine.api

import app.croptop.core.model.DocRect

/** Native-resolution crop of a merged document, stored as straight-alpha sRGB RGBA bytes. */
data class DocumentRegionPreview(val region: DocRect, val rgba: ByteArray) {
    init {
        require(!region.isEmpty && region.width <= MAX_DIMENSION && region.height <= MAX_DIMENSION)
        require(rgba.size.toLong() == region.width.toLong() * region.height * 4L)
    }

    companion object {
        const val MAX_DIMENSION = 512
    }
}
