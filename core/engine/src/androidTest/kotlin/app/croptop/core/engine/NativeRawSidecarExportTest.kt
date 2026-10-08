package app.croptop.core.engine

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRawSidecarExportTest {
    @Test
    fun appPrivateRawExportAtomicallyCreatesAndReplacesSiblingXmp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = File(context.cacheDir, "sidecar-export-test.dng").apply {
            context.assets.open("raw/synthetic.dng").use { input -> outputStream().use(input::copyTo) }
        }
        val sidecar = File(context.cacheDir, "sidecar-export-test.xmp")
        val engine = NativeEngine(context)
        val first = DevelopSettings(basic = BasicTone(exposure = -1.25f))
        val second = DevelopSettings(basic = BasicTone(exposure = 0.75f, contrast = 18f))
        try {
            val written = engine.exportRawDevelopSidecar(DocumentSource.AppFile(raw.absolutePath), first)
            assertEquals(sidecar.length(), written)
            assertEquals(first, NativeRawBridge.readDevelopSidecar(sidecar.readText()))

            val replacementBytes = engine.exportRawDevelopSidecar(DocumentSource.AppFile(raw.absolutePath), second)
            assertEquals(sidecar.length(), replacementBytes)
            assertEquals(second, NativeRawBridge.readDevelopSidecar(sidecar.readText()))
        } finally {
            engine.close()
            raw.delete()
            sidecar.delete()
        }
    }

    @Test
    fun safTreeRawExportCreatesAndReplacesSiblingXmp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val raw = File(context.cacheDir, "saf-export-test.dng").apply {
            context.assets.open("raw/synthetic.dng").use { input -> outputStream().use(input::copyTo) }
        }
        val sidecar = File(context.cacheDir, "saf-export-test.xmp").apply { delete() }
        RawSidecarDocumentsProvider.rawFile = raw
        RawSidecarDocumentsProvider.xmpFile = null
        val tree = DocumentsContract.buildTreeDocumentUri(
            "${testContext.packageName}.raw-sidecar-test",
            RawSidecarDocumentsProvider.ROOT_ID,
        )
        val imageUri = DocumentsContract.buildDocumentUriUsingTree(tree, RawSidecarDocumentsProvider.RAW_ID)
        val engine = NativeEngine(context)
        val first = DevelopSettings(basic = BasicTone(exposure = -2f))
        val second = DevelopSettings(basic = BasicTone(exposure = 0.5f, saturation = 12f))
        try {
            val firstBytes = engine.exportRawDevelopSidecar(DocumentSource.ContentUri(imageUri.toString()), first)
            assertTrue(sidecar.isFile)
            assertEquals(sidecar.length(), firstBytes)
            assertEquals(first, NativeRawBridge.readDevelopSidecar(sidecar.readText()))

            val replacementBytes = engine.exportRawDevelopSidecar(DocumentSource.ContentUri(imageUri.toString()), second)
            assertEquals(sidecar.length(), replacementBytes)
            assertEquals(second, NativeRawBridge.readDevelopSidecar(sidecar.readText()))
        } finally {
            engine.close()
            RawSidecarDocumentsProvider.rawFile = null
            RawSidecarDocumentsProvider.xmpFile = null
            raw.delete()
            sidecar.delete()
        }
    }

    @Test
    fun rejectsNonRawContentEvenWhenDisplayNameHasRawExtension() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = File(context.cacheDir, "not-actually-raw.dng").apply { writeText("JPEG bytes are not RAW data") }
        val sidecar = File(context.cacheDir, "not-actually-raw.xmp")
        val engine = NativeEngine(context)
        try {
            val error = runCatching {
                engine.exportRawDevelopSidecar(DocumentSource.AppFile(image.absolutePath), DevelopSettings())
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(!sidecar.exists())
        } finally {
            engine.close()
            image.delete()
            sidecar.delete()
        }
    }
}
