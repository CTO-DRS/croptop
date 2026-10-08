// The schemas agents get for CropTop's data-shaped commands and adjustments.
package app.croptop.core.engine.api.schema

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.model.AdjustmentSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

object CommandSchemas {
    private val docs = mapOf(
        "app.croptop.core.engine.api.EditCommand" to "One document mutation. Send it with the `type` shown by each alternative.",
        "app.croptop.core.engine.api.EditCommand.AddLayer" to
            "Adds a layer. `index` counts from the top of its parent; the parent is the root when parentId is absent.",
        "app.croptop.core.model.AdjustmentSpec" to
            "The parameters of one adjustment: numbers carry min/max/default, choices their options, editors a value shape.",
    )

    fun editCommand(): JsonObject = JsonSchemaExporter(docs).schemaFor(serializer<EditCommand>().descriptor, "EditCommand")

    fun commandResult(): JsonObject = JsonSchemaExporter(docs).schemaFor(serializer<CommandResult>().descriptor, "CommandResult")

    fun adjustmentSpec(): JsonObject = JsonSchemaExporter(docs).schemaFor(serializer<AdjustmentSpec>().descriptor, "AdjustmentSpec")

    /** Every schema by name, the payload of the agent-facing "describe commands" call. */
    fun all(): JsonObject = buildJsonObject {
        put("editCommand", editCommand())
        put("commandResult", commandResult())
        put("adjustmentSpec", adjustmentSpec())
    }
}
