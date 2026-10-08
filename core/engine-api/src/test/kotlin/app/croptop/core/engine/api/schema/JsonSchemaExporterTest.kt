package app.croptop.core.engine.api.schema

import app.croptop.core.engine.api.EditCommand
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

@Serializable
enum class Flavor { SWEET, SOUR }

@JvmInline
@Serializable
value class Handle(val raw: Long)

@Serializable
data class Node(val name: String, val children: List<Node> = emptyList(), val flavor: Flavor? = null)

@Serializable
sealed interface Shape {
    @Serializable
    data class Circle(val radius: Double, val tag: Handle) : Shape

    @Serializable
    data object Empty : Shape
}

@Serializable
data class Everything(
    val flag: Boolean,
    val count: Int,
    val ratio: Float = 1f,
    val label: String? = null,
    val scores: Map<String, Double> = emptyMap(),
    val shape: Shape,
)

private fun JsonObject.defs() = getValue("\$defs").jsonObject
private fun JsonObject.props() = getValue("properties").jsonObject

class JsonSchemaExporterTest {
    private val exporter = JsonSchemaExporter()

    @Test
    fun primitivesEnumsAndInlineClassesMapToJsonTypes() {
        val schema = exporter.schemaFor(serializer<Everything>().descriptor)
        val everything = schema.defs().getValue("Everything").jsonObject
        assertEquals("boolean", everything.props().getValue("flag").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("integer", everything.props().getValue("count").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("number", everything.props().getValue("ratio").jsonObject.getValue("type").jsonPrimitive.content)
        val scores = everything.props().getValue("scores").jsonObject
        assertEquals("object", scores.getValue("type").jsonPrimitive.content)
        assertEquals("number", scores.getValue("additionalProperties").jsonObject.getValue("type").jsonPrimitive.content)
        val shape = exporter.schemaFor(serializer<Shape>().descriptor).defs().getValue("Shape").jsonObject
        val circle = shape.getValue("oneOf").jsonArray.map { it.jsonObject }.first { "radius" in it.props().keys }
        assertEquals("integer", circle.props().getValue("tag").jsonObject.getValue("type").jsonPrimitive.content, "inline classes collapse")
    }

    @Test
    fun onlyPropertiesWithoutDefaultsAreRequired() {
        val schema = exporter.schemaFor(serializer<Everything>().descriptor)
        val required = schema.defs().getValue("Everything").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("flag", "count", "shape"), required.toSet())
        assertFalse(schema.defs().getValue("Everything").jsonObject.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun nullablePropertiesAllowNull() {
        val label = exporter.schemaFor(serializer<Everything>().descriptor).defs().getValue("Everything").jsonObject
            .props().getValue("label").jsonObject
        val alternatives = label.getValue("anyOf").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }
        assertEquals(listOf("string", "null"), alternatives)
    }

    @Test
    fun sealedTypesBecomeOneOfWithATypeConstantPerSubclass() {
        val schema = exporter.schemaFor(serializer<Shape>().descriptor)
        val alternatives = schema.defs().getValue("Shape").jsonObject.getValue("oneOf").jsonArray.map { it.jsonObject }
        assertEquals(2, alternatives.size)
        val names = alternatives.map { it.props().getValue("type").jsonObject.getValue("const").jsonPrimitive.content }
        assertTrue(names.any { it.endsWith("Shape.Circle") } && names.any { it.endsWith("Shape.Empty") })
        val empty = alternatives.first { it.props().getValue("type").jsonObject.getValue("const").jsonPrimitive.content.endsWith("Empty") }
        assertEquals(setOf("type"), empty.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun enumsListTheirNames() {
        val node = exporter.schemaFor(serializer<Node>().descriptor).defs().getValue("Node").jsonObject
        val flavor = node.props().getValue("flavor").jsonObject.getValue("anyOf").jsonArray.first().jsonObject
        assertEquals(listOf("SWEET", "SOUR"), flavor.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun recursiveTypesTerminateWithARef() {
        val schema = exporter.schemaFor(serializer<Node>().descriptor)
        val children = schema.defs().getValue("Node").jsonObject.props().getValue("children").jsonObject
        assertEquals("array", children.getValue("type").jsonPrimitive.content)
        assertEquals("#/\$defs/Node", children.getValue("items").jsonObject.getValue("\$ref").jsonPrimitive.content)
        assertEquals(1, schema.defs().size)
    }

    @Test
    fun docsBecomeDescriptions() {
        val documented = JsonSchemaExporter(
            mapOf(
                "app.croptop.core.engine.api.schema.Node" to "A tree node.",
                "app.croptop.core.engine.api.schema.Node.name" to "Display name.",
            ),
        )
        val node = documented.schemaFor(serializer<Node>().descriptor).defs().getValue("Node").jsonObject
        assertEquals("A tree node.", node.getValue("description").jsonPrimitive.content)
        assertEquals("Display name.", node.props().getValue("name").jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun editCommandSchemaListsEveryCommandAndMatchesEncodedCommands() {
        val schema = CommandSchemas.editCommand()
        val alternatives = schema.defs().getValue("EditCommand").jsonObject.getValue("oneOf").jsonArray
        assertEquals(7, alternatives.size, "AddLayer, DeleteLayer, MoveLayer, SetLayerProps, SetBlendMode, Undo, Redo")
        val addLayer = alternatives.map { it.jsonObject }.first {
            it.props().getValue("type").jsonObject.getValue("const").jsonPrimitive.content.endsWith("AddLayer")
        }
        val required = addLayer.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("type", "id", "name", "kind"), required, "parentId and index have defaults")

        // A real encoded command only uses properties the schema lists, and includes every required one.
        val json = Json { classDiscriminator = "type" }
        val command = EditCommand.AddLayer(LayerId(5), "Sky", LayerKind.PIXEL, index = 2)
        val encoded = json.encodeToJsonElement(EditCommand.serializer(), command).jsonObject
        assertTrue(encoded.keys.all { it in addLayer.props().keys }, "encoded keys ${encoded.keys} vs schema ${addLayer.props().keys}")
        assertTrue(required.all { it in encoded.keys })
        assertNotNull(CommandSchemas.all()["adjustmentSpec"])
    }

    @Test
    fun adjustmentSpecSchemaCoversItsPolymorphicParameters() {
        val schema = CommandSchemas.adjustmentSpec()
        val text = schema.toString()
        assertTrue("\"params\"" in text && "\"presets\"" in text)
        assertTrue(schema.defs().keys.any { it.endsWith("ParamSpec") }, "ParamSpec should be a sealed definition: ${schema.defs().keys}")
        assertTrue(schema.defs().values.all { it is JsonObject })
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}
