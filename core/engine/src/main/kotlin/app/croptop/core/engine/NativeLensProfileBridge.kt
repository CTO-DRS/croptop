package app.croptop.core.engine

import android.content.Context

/**
 * Lens-profile correction (native `lens_profile`, lensfun database under CC BY-SA 3.0). A [Database] session loads lensfun XML files, matches a camera and
 * lens by name and corrects a layer for distortion, lateral chromatic aberration and vignetting at a given focal length and aperture. The result of
 * [Database.correct] is a new store the caller owns (`NativeSelectionBridge.destroyStore`).
 */
internal object NativeLensProfileBridge {
    class LensInfo(
        val index: Int,
        val maker: String,
        val model: String,
        val mount: String,
        val cropFactor: Float,
        val distortionEntries: Int,
        val tcaEntries: Int,
        val vignettingEntries: Int,
    )

    class Database : AutoCloseable {
        private var handle = nativeCreate()

        private fun live(): Long = handle.also { check(it != 0L) { "Lens database is closed" } }
        internal val id: Long get() = live()

        /** Adds the lenses and cameras of one lensfun XML file (call once per file). */
        fun addXml(xml: ByteArray) {
            check(nativeAddXml(live(), xml) == 1L) { nativeLastError().ifBlank { "Could not read the lensfun XML" } }
        }

        val lensCount: Int get() = nativeLensCount(live())

        fun findLens(maker: String, model: String): LensInfo? {
            val index = nativeFindLens(live(), maker, model)
            if (index < 0) return null
            val p = nativeLensInfo(live(), index).split('|')
            if (p.size != 7) return null
            return LensInfo(
                index,
                p[0],
                p[1],
                p[2],
                p[3].toFloatOrNull() ?: 1f,
                p[4].toIntOrNull() ?: 0,
                p[5].toIntOrNull() ?: 0,
                p[6].toIntOrNull() ?: 0,
            )
        }

        /** Crop factor of the camera body, or null when the body is not in the database. */
        fun cameraCropFactor(maker: String, model: String): Float? = nativeCameraCrop(live(), maker, model).takeIf { it > 0f }

        /** Corrects [layerHandle] for [lens] at [focalMm]/[aperture]; [imageCropFactor] is the crop factor of the camera that took the picture. */
        fun correct(
            lens: LensInfo,
            layerHandle: Long,
            focalMm: Float,
            aperture: Float,
            imageCropFactor: Float = lens.cropFactor,
            distortion: Boolean = true,
            chromaticAberration: Boolean = true,
            vignetting: Boolean = true,
            workerThreads: Int = 0,
        ): Long {
            val flags = (if (distortion) 1 else 0) or (if (chromaticAberration) 2 else 0) or (if (vignetting) 4 else 0)
            val out = nativeApply(live(), lens.index, layerHandle, focalMm, aperture, imageCropFactor, flags, workerThreads)
            check(out != 0L) { nativeLastError().ifBlank { "Lens correction failed" } }
            return out
        }

        override fun close() {
            if (handle != 0L) nativeDestroy(handle)
            handle = 0L
        }
    }

    /** Loads the pinned packaged Lensfun snapshot for reuse by synchronous RAW opens. */
    fun fromAssets(context: Context): Database {
        val database = Database()
        try {
            val files = context.assets.list("lensfun").orEmpty().filter { it.endsWith(".xml") }.sorted()
            check(files.isNotEmpty()) { "The packaged Lensfun database is missing" }
            for (file in files) context.assets.open("lensfun/$file").use { database.addXml(it.readBytes()) }
            check(database.lensCount > 0) { "The packaged Lensfun database contains no lens profiles" }
            return database
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }

    private external fun nativeLastError(): String
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(db: Long)
    private external fun nativeAddXml(db: Long, xml: ByteArray): Long
    private external fun nativeLensCount(db: Long): Int
    private external fun nativeFindLens(db: Long, maker: String, model: String): Int
    private external fun nativeCameraCrop(db: Long, maker: String, model: String): Float
    private external fun nativeLensInfo(db: Long, index: Int): String
    private external fun nativeApply(
        db: Long,
        lensIndex: Int,
        storeHandle: Long,
        focalMm: Float,
        aperture: Float,
        imageCropFactor: Float,
        flags: Int,
        workerThreads: Int,
    ): Long
}
