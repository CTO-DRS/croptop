package app.croptop.core.agent

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A text model that can plan edits: Gemini Nano on devices that ship it.
 * No implementation is bound yet, so "Ask" stays hidden.
 */
interface AskModel {
    /** Null when ready; otherwise a user-facing reason (e.g. "Downloading the on-device model…"). */
    suspend fun unavailableReason(): String?

    suspend fun complete(system: String, prompt: String): String
}

@Module
@InstallIn(SingletonComponent::class)
interface AskModelModule {
    @BindsOptionalOf
    fun askModel(): AskModel
}

/** One tool call the model proposed; [why] is shown to the user before anything runs. */
data class PlannedStep(val tool: String, val args: JsonObject, val why: String)

sealed interface AskPlan {
    data class Steps(val steps: List<PlannedStep>) : AskPlan

    data class Failed(val message: String) : AskPlan
}

/**
 * Turns a request like "hide the background and rename the top layer to Sky" into validated tool
 * calls over [AgentTools], the same surface MCP agents use, so everything stays undoable. Nothing
 * runs until the user approves the listed steps.
 */
class AskPlanner(private val tools: AgentTools, private val docs: OpenDocuments) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Tools a planner may use: reading and undoable edits; no document creation. */
    private val allowed = setOf("list_layers", "add_layer", "delete_layer", "set_layer", "move_layer", "undo", "redo")

    suspend fun systemPrompt(): String {
        val context = runCatching { tools.call("list_layers", JsonObject(emptyMap())).first }.getOrDefault("(no document)")
        val toolLines = tools.all.filter { it.name in allowed && it.name != "list_layers" }.joinToString("\n") { t ->
            "- ${t.name}: ${t.description} Arguments (JSON schema): ${t.inputSchema["properties"]}"
        }
        return """
            You operate the photo editor CropTop. Turn the user's request into tool calls.
            Reply with JSON only, no prose: {"steps":[{"tool":"<name>","args":{...},"why":"<short reason>"}]}
            Use only these tools:
            $toolLines
            Omit document_id (the active document is used). Use layer ids from this layer list:
            $context
            If the request can't be done with these tools, reply {"steps":[],"error":"<one sentence why>"}.
        """.trimIndent()
    }

    /** Tolerant parse: takes the first JSON object in [text] and keeps only known tools. */
    fun parse(text: String): AskPlan {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return AskPlan.Failed("The assistant didn't return a plan. Try rephrasing.")
        val root = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return AskPlan.Failed("The assistant's plan couldn't be read. Try rephrasing.")
        root["error"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return AskPlan.Failed(it) }
        val steps = runCatching { root["steps"]?.jsonArray.orEmpty() }.getOrDefault(emptyList()).mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val tool = o["tool"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (tool !in allowed) return@mapNotNull null
            val args = runCatching { o["args"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            PlannedStep(tool, JsonObject(args - "document_id"), o["why"]?.jsonPrimitive?.content.orEmpty())
        }
        return if (steps.isEmpty()) AskPlan.Failed("Nothing to do for that request.") else AskPlan.Steps(steps.take(MAX_STEPS))
    }

    suspend fun plan(model: AskModel, request: String): AskPlan {
        if (docs.active.value == null) return AskPlan.Failed("Open a document first.")
        model.unavailableReason()?.let { return AskPlan.Failed(it) }
        val reply = runCatching { model.complete(systemPrompt(), request) }.getOrElse {
            return AskPlan.Failed("The on-device assistant isn't responding. Try again.")
        }
        return parse(reply)
    }

    /** Runs approved steps in order; stops at the first error. Returns a one-line summary. */
    suspend fun apply(steps: List<PlannedStep>): String {
        steps.forEachIndexed { i, s ->
            val (out, error) = tools.call(s.tool, s.args)
            if (error) return "Stopped at step ${i + 1}: $out"
        }
        return if (steps.size == 1) "Done" else "Done: ${steps.size} steps (undo with Ctrl+Z)"
    }

    companion object {
        const val MAX_STEPS = 12
    }
}

/** App-level entry point: present only when an [AskModel] is bound. */
@Singleton
class AskService @Inject constructor(model: Optional<AskModel>, docs: OpenDocuments) {
    val model: AskModel? = model.orElse(null)
    val planner = AskPlanner(AgentTools(docs), docs)
    val available: Boolean get() = model != null
}
