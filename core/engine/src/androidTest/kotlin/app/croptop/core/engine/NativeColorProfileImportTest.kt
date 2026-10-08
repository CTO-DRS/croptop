package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.WorkingSpace
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeColorProfileImportTest {
    @Test
    fun cancellingProfileJobBeforeItStartsLeavesProfileAndHistoryUntouched() = runBlocking {
        val engine = NativeEngine()
        val source = ColorProfileRef.BuiltIn(WorkingSpace.DISPLAY_P3)
        val target = ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Cancelled profile conversion", size = DocSize(16, 16), profile = source),
        ) as NativeDocument
        try {
            val historyBefore = document.history.value.current
            val lockField = NativeDocument::class.java.getDeclaredField("lock").apply { isAccessible = true }
            val documentLock = checkNotNull(lockField.get(document))
            val job = synchronized(documentLock) {
                document.applyColorProfileJob(target, convertPixels = true).also { it.cancel() }
            }

            val result = job.await() as CommandResult.Rejected
            assertEquals(RejectionCode.CANCELLED, result.code)
            assertEquals(source, document.info.value.profile)
            assertEquals(historyBefore, document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun profileJobOwnsEmbeddedBytesBeforeReturningToCaller() = runBlocking {
        val engine = NativeEngine()
        val sourceProfile = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3)
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Profile job byte ownership",
                size = DocSize(16, 16),
                profile = ColorProfileRef.BuiltIn(WorkingSpace.SRGB),
            ),
        ) as NativeDocument
        try {
            val icc = sourceProfile.iccBytes()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(icc)
                .joinToString("") { "%02x".format(it) }
            val target = ColorProfileRef.Embedded("Display P3", hash)
            val lockField = NativeDocument::class.java.getDeclaredField("lock").apply { isAccessible = true }
            val documentLock = checkNotNull(lockField.get(document))
            val job = synchronized(documentLock) {
                document.applyColorProfileJob(target, icc)
                    .also { icc.fill(0) }
            }

            assertTrue(job.await() is CommandResult.Accepted)
            assertEquals(target, document.info.value.profile)
        } finally {
            document.close()
            sourceProfile.close()
            engine.close()
        }
    }

    @Test
    fun conversionRejectsUnsupportedColorLayerWithoutChangingProfileOrHistory() {
        val engine = NativeEngine()
        val source = ColorProfileRef.BuiltIn(WorkingSpace.DISPLAY_P3)
        val target = ColorProfileRef.BuiltIn(WorkingSpace.SRGB)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Unsupported profile layer", size = DocSize(16, 16), profile = source),
        )
        try {
            assertTrue(document.execute(EditCommand.AddLayer(LayerId(1234), "Text", LayerKind.TEXT)) is CommandResult.Accepted)
            val historyBefore = document.history.value.current
            val rejected = document.applyColorProfile(target, convertPixels = true) as CommandResult.Rejected
            assertEquals(app.croptop.core.engine.api.RejectionCode.UNSUPPORTED, rejected.code)
            assertEquals(source, document.info.value.profile)
            assertEquals(historyBefore, document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun tiffAndFlattenedPsdImportInstallAndRetainEmbeddedRgbProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = engine.createTileStore(width = 8, height = 8, format = 0)
        val profile = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3)
        val files = mutableListOf<File>()
        val documents = mutableListOf<app.croptop.core.engine.api.DocumentHandle>()
        try {
            val icc = profile.iccBytes()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(icc)
                .joinToString("") { "%02x".format(it) }
            val expectedProfile = ColorProfileRef.Embedded("Display P3", hash)
            for (kind in listOf(NativeCodecExtraBridge.Kind.TIFF, NativeCodecExtraBridge.Kind.PSD)) {
                val output = ByteArrayOutputStream()
                NativeCodecExtraBridge.encode(source, kind, output, icc)
                val file = File(context.cacheDir, "croptop-profile-import-${kind.name.lowercase()}-${System.nanoTime()}")
                file.writeBytes(output.toByteArray())
                files += file

                val document = engine.open(DocumentSource.AppFile(file.absolutePath), OpenOptions())
                documents += document
                assertEquals("$kind profile reference", expectedProfile, document.info.value.profile)
                assertArrayEquals("$kind retained source ICC", icc, (document as NativeDocument).importedRasterMetadata?.iccProfile)
            }
        } finally {
            documents.forEach { it.close() }
            files.forEach(File::delete)
            profile.close()
            engine.destroyTileStore(source)
            engine.close()
        }
    }

    @Test
    fun layeredPsdImportInstallsTheProfileFromItsImportHolder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = engine.createTileStore(width = 8, height = 8, format = 0)
        val profile = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3)
        val file = File(context.cacheDir, "croptop-profile-import-layered-${System.nanoTime()}")
        var document: app.croptop.core.engine.api.DocumentHandle? = null
        try {
            val icc = profile.iccBytes()
            val output = ByteArrayOutputStream()
            NativeCodecExtraBridge.encodeLayered(
                records = listOf(
                    NativeCodecExtraBridge.PsdRecord(0, source, "Bottom", "norm", 255, 255, true, false),
                    NativeCodecExtraBridge.PsdRecord(0, source, "Top", "norm", 255, 255, true, false),
                ),
                composite = source,
                output = output,
                iccProfile = icc,
            )
            file.writeBytes(output.toByteArray())

            document = engine.open(DocumentSource.AppFile(file.absolutePath), OpenOptions())
            assertEquals(
                ColorProfileRef.Embedded(
                    "Display P3",
                    java.security.MessageDigest.getInstance("SHA-256").digest(icc).joinToString("") {
                        "%02x".format(it)
                    },
                ),
                document.info.value.profile,
            )
            assertArrayEquals(icc, (document as NativeDocument).importedRasterMetadata?.iccProfile)
            assertEquals(2, document.layers.value.flatten().count { it.kind == LayerKind.PIXEL })
        } finally {
            document?.close()
            file.delete()
            profile.close()
            engine.destroyTileStore(source)
            engine.close()
        }
    }

    @Test
    fun nonRgbEmbeddedProfilesFallBackToSrgbAndReportWhy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = engine.createTileStore(width = 8, height = 8, format = 0)
        val profile = NativeColorManagementBridge.Profile.genericCmyk()
        val file = File(context.cacheDir, "croptop-profile-import-cmyk-${System.nanoTime()}.tif")
        var document: app.croptop.core.engine.api.DocumentHandle? = null
        try {
            val output = ByteArrayOutputStream()
            NativeCodecExtraBridge.encode(source, NativeCodecExtraBridge.Kind.TIFF, output, profile.iccBytes())
            file.writeBytes(output.toByteArray())

            document = engine.open(DocumentSource.AppFile(file.absolutePath), OpenOptions())
            assertEquals(ColorProfileRef.BuiltIn(WorkingSpace.SRGB), document.info.value.profile)
            val metadata = (document as NativeDocument).importedRasterMetadata
            assertEquals(null, metadata?.iccProfile)
            assertTrue(metadata?.warnings.orEmpty().any { "cmyk ICC profile is not supported" in it })
            assertEquals(metadata?.warnings.orEmpty(), document.openWarnings)
        } finally {
            document?.close()
            file.delete()
            profile.close()
            engine.destroyTileStore(source)
            engine.close()
        }
    }
}
