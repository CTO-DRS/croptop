package app.croptop.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.croptop.core.model.ColorGradient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class GradientRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `custom gradients persist update and delete without accepting built-in ids`() = runTest(UnconfinedTestDispatcher()) {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(UnconfinedTestDispatcher())) {
            tmp.newFile("gradients.preferences_pb")
        }
        val repo = DataStoreGradientRepository(store)
        val saved = repo.save("  Sunset  ", ColorGradient.BlackToWhite)
        assertEquals("Sunset", saved.name)
        assertTrue(saved.id > 0)
        assertEquals(listOf(saved), repo.customPresets.first())
        assertFalse(repo.update(-1, "Built in", ColorGradient.BlackToWhite))
        assertFalse(repo.delete(-1))
        assertTrue(repo.update(saved.id, "Dusk", ColorGradient.BlackToWhite))
        assertEquals("Dusk", repo.customPresets.first().single().name)
        assertTrue(repo.delete(saved.id))
        assertTrue(repo.customPresets.first().isEmpty())
    }
}
