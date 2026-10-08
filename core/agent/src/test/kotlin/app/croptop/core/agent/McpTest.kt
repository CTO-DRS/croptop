package app.croptop.core.agent

import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before

class McpTest {
    private val engine = FakeEngine()
    private val docs = OpenDocuments(engine)
    private val protocol = McpProtocol(AgentTools(docs), docs)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun call(method: String, params: String = "{}", id: Int = 1) =
        Json.parseToJsonElement(protocol.handle("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")!!).jsonObject

    private suspend fun tool(name: String, args: String = "{}"): Pair<String, Boolean> {
        val r = call("tools/call", """{"name":"$name","arguments":$args}""")["result"]!!.jsonObject
        return r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content to r["isError"]!!.jsonPrimitive.content.toBoolean()
    }

    @Test
    fun `initialize, list tools, notifications`() = runTest {
        var client: String? = null
        protocol.onClient = { client = it }
        val init = call("initialize", """{"protocolVersion":"2025-06-18","clientInfo":{"name":"claude-desktop","version":"1"}}""")
        assertEquals("CropTop", init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("claude-desktop", client)
        assertNull(protocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val names = call("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("list_layers" in names && "set_layer" in names && "undo" in names)
        assertEquals(-32601, call("nope")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32700, Json.parseToJsonElement(protocol.handle("{not json")!!).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `agent edits the active document and they are undoable`() = runTest {
        assertTrue(tool("list_layers").second) // no document yet → friendly error
        val h = engine.newDocument(NewDocumentSpec(name = "Beach", size = DocSize(4000, 3000)))
        docs.register(h)
        assertTrue(tool("list_documents").first.contains("Beach"))
        val (added, err) = tool("add_layer", """{"name":"Warm tone"}""")
        assertTrue(!err, added)
        val id = added.substringAfter("id=").toLong()
        assertEquals("ok", tool("set_layer", """{"layer_id":$id,"opacity":40,"blend_mode":"soft_light"}""").first)
        val layers = tool("list_layers").first
        assertTrue(layers.contains("Warm tone") && layers.contains("opacity=40%") && layers.contains("soft_light"), layers)
        assertTrue(tool("set_layer", """{"layer_id":$id,"opacity":400}""").second) // validated
        assertEquals("ok", tool("undo").first)
        assertEquals("ok", tool("undo").first)
        assertTrue(!tool("list_layers").first.contains("Warm tone"))
        val read = call(
            "resources/read",
            """{"uri":"document://active/history"}""",
        )["result"]!!.jsonObject["contents"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(read.contains("New Layer"), read)
    }

    @Test
    fun `http transport enforces token, origin and serves json-rpc`() = runTest {
        val server = McpServer(protocol)
        server.start(0, "secret-token")
        val port = server.status.value.port
        try {
            // Raw socket client: HttpURLConnection silently drops the (restricted) Origin header.
            fun post(body: String, token: String? = "secret-token", origin: String? = null): Pair<Int, String> {
                java.net.Socket("127.0.0.1", port).use { sock ->
                    val bytes = body.toByteArray()
                    val head = buildString {
                        append("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n")
                        token?.let { append("Authorization: Bearer $it\r\n") }
                        origin?.let { append("Origin: $it\r\n") }
                        append("Content-Length: ${bytes.size}\r\n\r\n")
                    }
                    sock.getOutputStream().apply {
                        write(head.toByteArray())
                        write(bytes)
                        flush()
                    }
                    val raw = sock.getInputStream().readBytes().decodeToString()
                    val code = raw.substringAfter(' ').substringBefore(' ').toInt()
                    return code to raw.substringAfter("\r\n\r\n")
                }
            }
            val ping = """{"jsonrpc":"2.0","id":7,"method":"ping"}"""
            assertEquals(401, post(ping, token = null).first)
            assertEquals(401, post(ping, token = "wrong").first)
            assertEquals(403, post(ping, origin = "https://evil.example").first)
            val (code, body) = post(ping, origin = "http://localhost:3000")
            assertEquals(200, code)
            assertTrue(body.contains("\"id\":7"), body)
            assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `malformed requests are refused one by one and the server keeps serving`() = runTest {
        val server = McpServer(protocol)
        server.start(0, "secret-token")
        val port = server.status.value.port
        fun raw(request: String): Int = java.net.Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().apply { write(request.toByteArray()); flush() }
            val text = runCatching { sock.getInputStream().readBytes().decodeToString() }.getOrDefault("")
            text.substringAfter(' ', "").substringBefore(' ').toIntOrNull() ?: -1
        }
        try {
            val auth = "Authorization: Bearer secret-token\r\n"
            assertEquals(400, raw("POST /mcp HTTP/1.1\r\n${auth}Content-Length: -5\r\n\r\n"))
            assertEquals(400, raw("POST /mcp HTTP/1.1\r\n${auth}Content-Length: 999999999\r\n\r\n"))
            assertEquals(400, raw("POST /mcp HTTP/1.1\r\n${auth}Content-Length: nine\r\n\r\n"))
            assertEquals(400, raw("POST /mcp HTTP/1.1\r\n" + "X-Junk: 1\r\n".repeat(100) + "\r\n"))
            // A client that connects and hangs up at once must not stop the server.
            java.net.Socket("127.0.0.1", port).close()
            val ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"
            assertEquals(200, raw("POST /mcp HTTP/1.1\r\n${auth}Content-Length: ${ping.length}\r\n\r\n$ping"))
            assertTrue(server.status.value.running)
        } finally {
            server.stop()
        }
    }
}
