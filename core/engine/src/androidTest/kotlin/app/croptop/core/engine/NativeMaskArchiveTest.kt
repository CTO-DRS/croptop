package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.io.CroptopManifest
import app.croptop.core.io.CroptopManifestCodec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMaskArchiveTest {
    @Test
    fun maskPixelsAndHideDefaultSurviveCroptopSaveOpen() = runBlocking {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext<Context>())
        val target = java.io.File.createTempFile("croptop-mask-roundtrip", ".croptop")
        val corrupted = java.io.File.createTempFile("croptop-mask-missing", ".croptop")
        val document = engine.newDocument(
            NewDocumentSpec("Mask archive", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val color = RgbaColor(0.9f, 0.3f, 0.1f, 1f)
            assertTrue(document.fill(FillSource.Solid(color)) is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 24f, 24f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.addLayerMask(layer, MaskFill.REVEAL_SELECTION) is CommandResult.Accepted)
            document.save(SaveTarget.AppFile(target.absolutePath))

            val reopened = engine.open(DocumentSource.AppFile(target.absolutePath))
            try {
                val reopenedLayer = checkNotNull(reopened.layers.value.primary)
                assertEquals(true, reopened.layers.value.find(reopenedLayer)?.mask?.enabled)
                assertEquals(color.a, reopened.sample(DocPoint(12f, 12f), SampleMode.MERGED).color.a, 0.01f)
                assertEquals(0f, reopened.sample(DocPoint(3f, 3f), SampleMode.MERGED).color.a, 0.01f)
            } finally {
                reopened.close()
            }

            val entries = ZipInputStream(target.inputStream()).use { zip ->
                buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        put(entry.name, zip.readBytes())
                        zip.closeEntry()
                    }
                }
            }
            val manifest = CroptopManifestCodec.decode(checkNotNull(entries["manifest.json"])).manifest
            val maskKeys = manifest.flatten().filter { it.maskPayloadPresent }.map { CroptopManifest.maskPayloadUuid(it.uuid) }
            ZipOutputStream(corrupted.outputStream()).use { zip ->
                entries.filterKeys { name -> maskKeys.none { name.startsWith("tiles/$it/") } }.forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }.value
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            this.crc = crc
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val corruptedOpen = runCatching { engine.open(DocumentSource.AppFile(corrupted.absolutePath)) }
            corruptedOpen.getOrNull()?.close()
            assertTrue(corruptedOpen.exceptionOrNull() is IOException)
        } finally {
            document.close()
            engine.close()
            target.delete()
            corrupted.delete()
        }
    }
}
