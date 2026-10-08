package app.croptop.core.agent

import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

class AskTest {
    private val engine = FakeEngine()
    private val docs = OpenDocuments(engine)
    private val planner = AskPlanner(AgentTools(docs), docs)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Scripted(val reply: String, val reason: String? = null) : AskModel {
        var lastSystem = ""

        override suspend fun unavailableReason() = reason

        override suspend fun complete(system: String, prompt: String): String {
            lastSystem = system
            return reply
        }
    }

    @Test
    fun `plans only allowed tools, strips document ids, tolerates prose around json`() {
        val plan = planner.parse(
            """Sure! {"steps":[{"tool":"add_layer","args":{"name":"Sky","document_id":9},"why":"new layer"},""" +
                """{"tool":"new_document","args":{"width":1,"height":1}},{"tool":"undo","args":{}}]} hope that helps""",
        )
        val steps = assertIs<AskPlan.Steps>(plan).steps
        assertEquals(listOf("add_layer", "undo"), steps.map { it.tool })
        assertEquals(setOf("name"), steps[0].args.keys)
        assertIs<AskPlan.Failed>(planner.parse("no json here"))
        assertEquals("Can't paint", assertIs<AskPlan.Failed>(planner.parse("""{"steps":[],"error":"Can't paint"}""")).message)
    }

    @Test
    fun `end to end on the fake engine, undoable`() = runTest {
        val doc = engine.newDocument(NewDocumentSpec(size = DocSize(100, 100)))
        docs.register(doc)
        docs.activate(doc)
        val model = Scripted("""{"steps":[{"tool":"add_layer","args":{"name":"Sky"},"why":"requested"}]}""")
        val steps = assertIs<AskPlan.Steps>(planner.plan(model, "add a layer called Sky")).steps
        assertTrue("list_layers" !in model.lastSystem.substringAfter("Use only these tools:").substringBefore("Omit"))
        assertTrue("Background" in model.lastSystem) // current layers are in the context
        assertEquals("Done", planner.apply(steps))
        assertTrue(doc.layers.value.flatten().any { it.name == "Sky" })
        doc.undo()
        assertTrue(doc.layers.value.flatten().none { it.name == "Sky" })
    }

    @Test
    fun `unavailable model and no document explain themselves`() = runTest {
        assertEquals("Open a document first.", assertIs<AskPlan.Failed>(planner.plan(Scripted("{}"), "x")).message)
        val doc = engine.newDocument(NewDocumentSpec(size = DocSize(10, 10)))
        docs.register(doc)
        docs.activate(doc)
        assertEquals("Downloading…", assertIs<AskPlan.Failed>(planner.plan(Scripted("{}", "Downloading…"), "x")).message)
    }
}
