package app.croptop.core.agent

import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class AgentExtendedToolsTest {
    private val dir: File = Files.createTempDirectory("agent-exports").toFile()
    private var allow = true
    private val gate = object : ExportGate {
        override val directory: File = dir
        val asked = mutableListOf<String>()
        override suspend fun confirm(description: String): Boolean {
            asked += description
            return allow
        }
    }
    private val engine = FakeEngine()
    private val docs = OpenDocuments(engine)
    private val tools = AgentTools(docs, gate)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun open() = engine.newDocument(NewDocumentSpec(name = "Beach", size = DocSize(400, 300))).also { docs.register(it) }

    @Test
    fun selectModesDriveTheSelection() = runTest {
        val doc = open()
        assertFalse(tools.call("select", json("""{"mode":"all"}""")).second)
        assertFalse(tools.call("select", json("""{"mode":"rectangle","x":10,"y":10,"width":50,"height":40}""")).second)
        assertTrue(tools.call("select", json("""{"mode":"rectangle","x":10}""")).second, "a rectangle needs a size")
        assertTrue(tools.call("select", json("""{"mode":"banana"}""")).second)
        assertFalse(tools.call("select", json("""{"mode":"none"}""")).second)
        assertEquals(app.croptop.core.model.SelectionInfo.None, doc.selection.value)
    }

    @Test
    fun adjustmentsListAndApplyAsALayerOrDestructively() = runTest {
        val doc = open()
        val listing = tools.call("list_adjustments", json("{}")).first
        assertTrue("brightnessContrast" in listing && "photoFilter" in listing)
        val before = doc.layers.value.flatten().size
        val added = tools.call("apply_adjustment", json("""{"adjustment":"photoFilter","values":{"filter":"warming85","density":40}}"""))
        assertFalse(added.second, added.first)
        assertEquals(before + 1, doc.layers.value.flatten().size)
        assertTrue(tools.call("apply_adjustment", json("""{"adjustment":"nope"}""")).second)
        assertTrue(tools.call("apply_adjustment", json("""{"adjustment":"brightnessContrast","values":{"colour":1}}""")).second)
        val steps = doc.history.value.entries.size
        assertFalse(tools.call("undo", json("{}")).second)
        assertTrue(doc.history.value.entries.size >= steps)
    }

    @Test
    fun exportAsksTheGateAndRespectsDenial() = runTest {
        open()
        val ok = tools.call("export", json("""{"format":"jpeg","quality":80,"scale_percent":50,"name":"beach"}"""))
        assertFalse(ok.second, ok.first)
        assertTrue(File(dir, "beach.jpg").isFile)
        assertEquals(1, (gate.asked).size)
        allow = false
        val denied = tools.call("export", json("""{"format":"png"}"""))
        assertTrue(denied.second)
        assertTrue("did not allow" in denied.first)
        assertTrue(tools.call("export", json("""{"format":"tiff"}""")).second)
    }

    @Test
    fun previewReturnsAnImageForTheClient() = runTest {
        open()
        val result = tools.call("get_preview", json("""{"max_px":256}"""))
        assertFalse(result.second, result.first)
        // The fake engine writes a placeholder; the real engine writes a PNG. Either way the bytes are handed to the protocol once.
        assertNotNull(tools.takeImage())
        assertNull(tools.takeImage())
    }
}
