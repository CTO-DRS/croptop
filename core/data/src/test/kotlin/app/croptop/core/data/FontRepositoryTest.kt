package app.croptop.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.croptop.core.model.FontFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class FontRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `families group once, favorites toggle, recents stay unique and short`() = runTest(UnconfinedTestDispatcher()) {
        var scans = 0
        val store = PreferenceDataStoreFactory.create(scope = TestScope(UnconfinedTestDispatcher())) { tmp.newFile("f.preferences_pb") }
        val repo = DataStoreFontRepository(
            {
                scans++
                listOf(FontFace("Roboto", "Regular", "/r"), FontFace("Roboto", "Bold", "/b", weight = 700), FontFace("Inter", "Regular", "/i"))
            },
            store,
            NoGoogleFonts,
        )
        assertEquals(listOf("Inter", "Roboto"), repo.families.first().map { it.name })
        repo.families.first()
        assertEquals(1, scans)
        repo.toggleFavorite("Inter")
        assertEquals(setOf("Inter"), repo.favorites.first())
        repo.toggleFavorite("Inter")
        assertEquals(emptySet(), repo.favorites.first())
        (1..10).forEach { repo.markUsed("F$it") }
        repo.markUsed("F5")
        assertEquals(listOf("F5", "F10", "F9", "F8", "F7", "F6", "F4", "F3"), repo.recent.first())
    }
}

private object NoGoogleFonts : GoogleFonts {
    override val installed = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
    override suspend fun catalog() = emptyList<GoogleFontListing>()
    override suspend fun install(family: GoogleFontListing, onProgress: (Float) -> Unit) = Unit
    override fun uninstall(family: String) = Unit
    override fun faces() = emptyList<app.croptop.core.model.FontFace>()
}
