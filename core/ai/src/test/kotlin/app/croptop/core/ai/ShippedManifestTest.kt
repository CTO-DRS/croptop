package app.croptop.core.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The manifest the app ships (app/src/main/assets/ai/models.json) must be fully valid: nothing silently left out. */
class ShippedManifestTest {
    private val manifest = File("../../app/src/main/assets/ai/models.json")

    @Test
    fun everyShippedModelPassesTheRegistryChecks() {
        assertTrue(manifest.isFile, "manifest not found at ${manifest.absolutePath}")
        val registry = ModelRegistry.parse(manifest.readText())
        assertEquals(emptyList(), registry.rejected, "rejected entries")
        assertTrue(registry.models.size >= 3)
        assertEquals(setOf(ModelTask.SEGMENT_PROMPT, ModelTask.SEGMENT_SUBJECT, ModelTask.MATTING, ModelTask.DEPTH, ModelTask.INPAINT, ModelTask.DENOISE, ModelTask.UPSCALE), registry.models.map { it.task }.toSet())
        for (model in registry.models) {
            assertTrue(model.license.attribution.isNotBlank(), "${model.id} needs attribution text")
            assertTrue(model.license.sourceUrl.startsWith("https://"), "${model.id} needs a source link")
        }
    }
}
