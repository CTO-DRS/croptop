package app.croptop.core.agent

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the UI shows: "Agent connected" chip, last tool, disconnect. */
data class AgentStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val client: String? = null,
    val lastTool: String? = null,
    val lastCallAt: Long = 0,
    val calls: Int = 0,
    val error: String? = null,
)

/**
 * Local MCP server: HTTP on 127.0.0.1 only, POST /mcp JSON-RPC,
 * bearer-token pairing, Origin check (DNS-rebinding protection), rate limit. Off until the user enables it.
 */
class McpServer(
    private val protocol: McpProtocol,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    @Volatile private var token: String = ""
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val recentCalls = ArrayDeque<Long>() // guarded by itself
    private val connections = kotlinx.coroutines.sync.Semaphore(MAX_CONNECTIONS)

    init {
        protocol.onClient = { name -> _status.value = _status.value.copy(client = name) }
        protocol.onToolCall = { name -> _status.value = _status.value.copy(lastTool = name, lastCallAt = clock(), calls = _status.value.calls + 1) }
    }

    fun start(port: Int, pairingToken: String) {
        stop()
        token = pairingToken
        val server = runCatching { ServerSocket(port, 16, InetAddress.getLoopbackAddress()) }.getOrElse {
            _status.value = AgentStatus(error = "Port $port is in use. Choose another port in Settings.")
            return
        }
        socket = server
        _status.value = AgentStatus(running = true, port = server.localPort)
        job = scope.launch {
            while (isActive && !server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                // Each connection fails on its own: a bad request, reset or timeout never reaches the accept loop. Beyond
                // MAX_CONNECTIONS at once, new ones are told to retry instead of piling up.
                if (!connections.tryAcquire()) {
                    launch { client.use { runCatching { respond(it.getOutputStream(), 503, "Busy — try again", extra = "Retry-After: 1\r\n") } } }
                    continue
                }
                launch {
                    try {
                        client.use { serve(it) }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        // The client went away or sent something unusable; nothing to answer.
                    } finally {
                        connections.release()
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
        _status.value = AgentStatus()
    }

    private suspend fun serve(s: Socket) {
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val request = readRequest(input) ?: return respond(out, 400, "Bad request")
        val origin = request.headers["origin"]
        when {
            origin != null && !isLocalOrigin(origin) -> respond(out, 403, "Forbidden origin")

            request.path.substringBefore('?') != "/mcp" -> respond(out, 404, "Not found")

            !authorized(request.headers["authorization"]) ->
                respond(out, 401, "Pair this agent in CropTop ▸ Settings ▸ AI agents", extra = "WWW-Authenticate: Bearer\r\n")

            request.method == "GET" -> respond(out, 405, "This server responds with JSON only", extra = "Allow: POST, DELETE\r\n")

            request.method == "DELETE" -> {
                _status.value = _status.value.copy(client = null)
                respond(out, 200, "")
            }

            request.method != "POST" -> respond(out, 405, "Method not allowed", extra = "Allow: POST, DELETE\r\n")

            !allowCall() -> respond(out, 429, "Too many requests — slow down", extra = "Retry-After: 2\r\n")

            else -> {
                val reply = withContext(Dispatchers.Main.immediate) { protocol.handle(request.body) }
                if (reply == null) respond(out, 202, "") else respond(out, 200, reply, contentType = "application/json")
            }
        }
    }

    private fun allowCall(): Boolean = synchronized(recentCalls) {
        val now = clock()
        while (recentCalls.isNotEmpty() && now - recentCalls.first() > 10_000) recentCalls.removeFirst()
        if (recentCalls.size >= 60) return false
        recentCalls.addLast(now)
        true
    }

    private fun authorized(header: String?): Boolean {
        val presented = header?.removePrefix("Bearer ")?.trim() ?: return false
        // Constant-time compare.
        return token.isNotEmpty() && MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val start = readLine(input)?.split(' ') ?: return null
        if (start.size < 2) return null
        val headers = mutableMapOf<String, String>()
        var headerLines = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (++headerLines > MAX_HEADERS) return null
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        // The whole request is bounded before anything is allocated: a missing, negative or oversized length is refused.
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: return null } ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(start[0], start[1], headers, body.decodeToString(0, read))
    }

    private fun respond(out: OutputStream, code: Int, body: String, contentType: String = "text/plain; charset=utf-8", extra: String = "") {
        val bytes = body.toByteArray()
        val reason = Reasons[code] ?: "Error"
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        private val Reasons = mapOf(
            200 to "OK",
            202 to "Accepted",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            429 to "Too Many Requests",
        )
        const val DEFAULT_PORT = 39510
        const val MAX_BODY = 1_048_576
        const val MAX_HEADERS = 64
        const val MAX_CONNECTIONS = 8

        fun newToken(): String {
            val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        fun isLocalOrigin(origin: String): Boolean {
            val host = origin.substringAfter("://").substringBefore('/').substringBeforeLast(':')
            return host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        }
    }
}
