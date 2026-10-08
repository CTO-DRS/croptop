// The set of models the app knows about, loaded from a bundled JSON manifest.
package app.croptop.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class RegistryDocument(val schema: Int = 1, val models: List<ModelSpec> = emptyList())

/** An entry that was left out and why, so a bad manifest line never hides the good ones and never fails silently. */
data class RegistryRejection(val id: String, val problems: List<String>)

class ModelRegistry private constructor(models: List<ModelSpec>, val rejected: List<RegistryRejection>) {
    private val byId: Map<String, ModelSpec> = models.associateBy { it.id }
    val models: List<ModelSpec> get() = byId.values.toList()

    operator fun get(id: String): ModelSpec? = byId[id]

    fun forTask(task: ModelTask): List<ModelSpec> = byId.values.filter { it.task == task }.sortedByDescending { it.version }

    fun encode(): String = json.encodeToString(RegistryDocument.serializer(), RegistryDocument(models = models))

    companion object {
        const val SCHEMA = 1
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Registers the valid entries of [specs] (later duplicates of an id are rejected). */
        fun of(specs: List<ModelSpec>): ModelRegistry {
            val accepted = LinkedHashMap<String, ModelSpec>()
            val rejected = mutableListOf<RegistryRejection>()
            for (spec in specs) {
                val problems = spec.problems().toMutableList()
                if (spec.id in accepted) problems += "duplicate id"
                if (problems.isEmpty()) accepted[spec.id] = spec else rejected += RegistryRejection(spec.id, problems)
            }
            return ModelRegistry(accepted.values.toList(), rejected)
        }

        /** Parses a manifest; throws [IllegalArgumentException] only when the document itself is unreadable. */
        fun parse(text: String): ModelRegistry {
            val document = try {
                json.decodeFromString(RegistryDocument.serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("model manifest is not valid: ${e.message}", e)
            }
            require(document.schema in 1..SCHEMA) { "model manifest schema ${document.schema} is not supported" }
            return of(document.models)
        }
    }
}
