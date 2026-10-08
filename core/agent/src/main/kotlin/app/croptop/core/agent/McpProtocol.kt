package app.croptop.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * MCP JSON-RPC handling (Streamable HTTP transport, JSON responses). Transport-independent so it is
 * unit-tested directly. Returns null for notifications (HTTP 202, no body).
 */
class McpProtocol(private val tools: AgentTools, private val docs: OpenDocuments, private val version: String = "0.1.0") {
    private val json = Json { ignoreUnknownKeys = true }

    /** Called with the client's name after initialize, and with each tool name when used. */
    var onClient: (String) -> Unit = {}
    var onToolCall: (String) -> Unit = {}

    suspend fun handle(body: String): String? {
        val req = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { return error(JsonNull, -32700, "Parse error") }
        val id = req["id"]
        val method = req["method"]?.jsonPrimitive?.contentOrNull ?: return error(id ?: JsonNull, -32600, "Invalid request")
        val params = (req["params"] as? JsonObject) ?: JsonObject(emptyMap())
        if (id == null) return null // notification (e.g. notifications/initialized)
        return when (method) {
            "initialize" -> {
                params["clientInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.let(onClient)
                result(
                    id,
                    buildJsonObject {
                        put("protocolVersion", params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: PROTOCOL_VERSION)
                        putJsonObject("capabilities") {
                            putJsonObject("tools") { put("listChanged", false) }
                            putJsonObject("resources") {}
                        }
                        putJsonObject("serverInfo") {
                            put("name", "CropTop")
                            put("version", version)
                        }
                        put(
                            "instructions",
                            "CropTop is a photo editor. Edits apply to the document the user has open and each one is an undoable history step. " +
                                "Call list_layers before editing layers. Keep the user informed of what you changed.",
                        )
                    },
                )
            }

            "ping" -> result(id, JsonObject(emptyMap()))

            "tools/list" -> result(id, buildJsonObject { put("tools", tools.toolsJson()) })

            "tools/call" -> {
                val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32602, "Missing tool name")
                onToolCall(name)
                val (text, isError) = tools.call(name, (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap()))
                val image = tools.takeImage()
                result(
                    id,
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                                if (image != null) {
                                    add(
                                        buildJsonObject {
                                            put("type", "image")
                                            put("data", java.util.Base64.getEncoder().encodeToString(image))
                                            put("mimeType", "image/png")
                                        },
                                    )
                                }
                            },
                        )
                        put("isError", isError)
                    },
                )
            }

            "resources/list" -> result(
                id,
                buildJsonObject {
                    put(
                        "resources",
                        buildJsonArray {
                            add(resource("document://active/info", "Active document", "Size, depth, profile and save state"))
                            add(resource("document://active/layers", "Layers", "Layer stack of the active document"))
                            add(resource("document://active/history", "History", "History steps of the active document"))
                        },
                    )
                },
            )

            "resources/read" -> {
                val uri = params["uri"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32602, "Missing uri")
                val tool = when (uri) {
                    "document://active/info" -> "get_document"
                    "document://active/layers" -> "list_layers"
                    "document://active/history" -> "get_history"
                    else -> return error(id, -32002, "Resource not found: $uri")
                }
                val (text, _) = tools.call(tool, JsonObject(emptyMap()))
                result(
                    id,
                    buildJsonObject {
                        put(
                            "contents",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("uri", uri)
                                        put("mimeType", "text/plain")
                                        put("text", text)
                                    },
                                )
                            },
                        )
                    },
                )
            }

            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun resource(uri: String, name: String, description: String) = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("description", description)
        put("mimeType", "text/plain")
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}
