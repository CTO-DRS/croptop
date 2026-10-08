package app.croptop.core.engine.api

import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenOptionsTest {
    @Test
    fun rawDevelopSettingsSurviveContractSerialization() {
        val options = OpenOptions(
            recoverJournal = false,
            rawDevelopSettings = DevelopSettings(basic = BasicTone(exposure = -1.25f, highlights = -24f)),
        )
        val encoded = Json.encodeToString(OpenOptions.serializer(), options)

        assertEquals(options, Json.decodeFromString(OpenOptions.serializer(), encoded))
    }

    @Test
    fun missingRawDevelopSettingsDecodeAsNeutralForExistingCallers() {
        val decoded = Json.decodeFromString(OpenOptions.serializer(), "{\"recoverJournal\":true}")

        assertEquals(null, decoded.rawDevelopSettings)
    }
}
