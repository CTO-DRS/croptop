// JSON Schema (draft 2020-12) from kotlinx.serialization descriptors: the command schema agents read.
package app.croptop.core.engine.api.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Describes exactly what `Json { }` (default class discriminator `type`, `encodeDefaults = false` for the app's Json) accepts and
 * emits for a `@Serializable` type, so an agent can build valid commands without reading Kotlin.
 *
 * - classes become objects with `additionalProperties: false`; properties with defaults are optional;
 * - sealed hierarchies become `oneOf` over their subclasses, each carrying a `type` constant equal to its `@SerialName`;
 * - inline value classes (`LayerId`) collapse to their underlying type; enums become string enums;
 * - every class and sealed type is emitted once under `$defs` and referenced by `$ref`, so recursive types terminate.
 *
 * [docs] maps a serial name (a class, or `Class.property`) to a `description`.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonSchemaExporter(private val docs: Map<String, String> = emptyMap()) {
    private val definitions = linkedMapOf<String, JsonElement>()
    private val inProgress = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>() // serial name -> $defs key

    /** A complete schema document for [root]. */
    fun schemaFor(root: SerialDescriptor, title: String = simpleName(root.serialName)): JsonObject {
        definitions.clear()
        inProgress.clear()
        names.clear()
        val body = describe(root)
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("title", title)
            for ((key, value) in body) put(key, value)
            if (definitions.isNotEmpty()) put("\$defs", JsonObject(definitions.toMap()))
        }
    }

    private fun describe(descriptor: SerialDescriptor): JsonObject {
        val core = if (descriptor.isInline) describe(descriptor.getElementDescriptor(0)) else describeKind(descriptor)
        return if (descriptor.isNullable) {
            buildJsonObject {
                put(
                    "anyOf",
                    buildJsonArray {
                        add(core)
                        add(buildJsonObject { put("type", "null") })
                    },
                )
            }
        } else {
            core
        }
    }

    private fun describeKind(descriptor: SerialDescriptor): JsonObject = when (val kind = descriptor.kind) {
        is PrimitiveKind -> primitive(kind)

        SerialKind.ENUM -> buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { for (i in 0 until descriptor.elementsCount) add(JsonPrimitive(descriptor.getElementName(i))) }
        }

        StructureKind.LIST -> buildJsonObject {
            put("type", "array")
            put("items", describe(descriptor.getElementDescriptor(0)))
        }

        StructureKind.MAP -> buildJsonObject {
            put("type", "object")
            put("additionalProperties", describe(descriptor.getElementDescriptor(1)))
        }

        StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> reference(descriptor)

        else -> JsonObject(emptyMap()) // open polymorphism and contextual serializers accept anything
    }

    private fun primitive(kind: PrimitiveKind): JsonObject = buildJsonObject {
        when (kind) {
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> put("type", "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> put("type", "string")
        }
    }

    /** Defines the type once under `$defs` and returns a `$ref` to it. */
    private fun reference(descriptor: SerialDescriptor): JsonObject {
        val name = definitionName(descriptor)
        if (name !in definitions && inProgress.add(name)) {
            definitions[name] = definition(descriptor)
            inProgress.remove(name)
        }
        return buildJsonObject { put("\$ref", "#/\$defs/$name") }
    }

    private fun definition(descriptor: SerialDescriptor): JsonObject = when (descriptor.kind) {
        PolymorphicKind.SEALED -> sealed(descriptor)
        else -> objectSchema(descriptor, discriminator = null)
    }

    private fun sealed(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        docs[descriptor.serialName]?.let { put("description", it) }
        // A sealed descriptor is (type: String, value: <one element descriptor per subclass>).
        val subclasses = descriptor.getElementDescriptor(1)
        put(
            "oneOf",
            buildJsonArray {
                for (i in 0 until subclasses.elementsCount) {
                    val sub = subclasses.getElementDescriptor(i)
                    add(sealedMember(sub))
                }
            },
        )
    }

    private fun sealedMember(sub: SerialDescriptor): JsonObject =
        if (sub.kind == PolymorphicKind.SEALED) reference(sub) else inlineObject(sub, discriminator = sub.serialName)

    private fun inlineObject(descriptor: SerialDescriptor, discriminator: String?): JsonObject = objectSchema(descriptor, discriminator)

    private fun objectSchema(descriptor: SerialDescriptor, discriminator: String?): JsonObject = buildJsonObject {
        put("type", "object")
        docs[descriptor.serialName]?.let { put("description", it) }
        val required = mutableListOf<String>()
        put(
            "properties",
            buildJsonObject {
                if (discriminator != null) {
                    put("type", buildJsonObject { put("const", discriminator) })
                    required += "type"
                }
                for (i in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(i)
                    val property = describe(descriptor.getElementDescriptor(i))
                    val doc = docs["${descriptor.serialName}.$name"]
                    put(name, if (doc == null) property else JsonObject(property + ("description" to JsonPrimitive(doc))))
                    if (!descriptor.isElementOptional(i)) required += name
                }
            },
        )
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }

    /** The serial name without its package ("EditCommand.AddLayer"); the full name is used if two types would collide. */
    private fun definitionName(descriptor: SerialDescriptor): String {
        val serialName = descriptor.serialName.removeSuffix("?")
        return names.getOrPut(serialName) {
            val short = serialName.split('.').dropWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".").ifEmpty { serialName }
            val clean = short.replace(Regex("[^A-Za-z0-9_.]"), "_")
            if (clean in names.values) serialName.replace(Regex("[^A-Za-z0-9_.]"), "_") else clean
        }
    }

    private fun simpleName(serialName: String) = serialName.removeSuffix("?").substringAfterLast('.')
}
