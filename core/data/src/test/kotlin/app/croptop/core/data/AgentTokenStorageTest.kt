package app.croptop.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AgentTokenStorageTest {
    @Test
    fun `the pairing token lives only in the device store that backups leave out`() = runTest {
        val dir = createTempDirectory().toFile()
        val prefs = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "croptop_prefs.preferences_pb") }
        val device = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "$DEVICE_STORE.preferences_pb") }
        val repo = DataStorePreferencesRepository(prefs, device)
        repo.setAgentSettings(AgentSettings(enabled = true, port = 40000, token = "secret", confirmExport = false))
        val read = repo.agentSettings.first()
        assertEquals(AgentSettings(enabled = true, port = 40000, token = "secret", confirmExport = false), read)
        assertNull(prefs.data.first()[stringPreferencesKey("agent.token")], "not in the backed-up preferences")
        assertEquals("secret", device.data.first()[stringPreferencesKey("agent.token")])
    }
}
