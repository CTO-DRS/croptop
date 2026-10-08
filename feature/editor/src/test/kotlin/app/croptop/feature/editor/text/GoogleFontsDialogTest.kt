package app.croptop.feature.editor.text

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.croptop.core.data.GoogleFontListing
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w900dp-h900dp-mdpi")
class GoogleFontsDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val catalog = listOf(
        GoogleFontListing("Roboto", "Sans Serif", 2, listOf("400", "700")),
        GoogleFontListing("Lora", "Serif", 38, listOf("400", "400i", "700")),
        GoogleFontListing("Pacifico", "Handwriting", 135, listOf("400")),
    )

    private fun show(ui: GoogleFontsUi, onDismiss: () -> Unit = {}) = compose.setContent {
        CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) { GoogleFontsDialog(ui, onDismiss) }
    }

    @Test
    fun `matching puts prefix matches first and popular families before obscure ones`() {
        assertEquals(listOf("Roboto", "Lora", "Pacifico"), catalog.matching("").map { it.family })
        assertEquals(listOf("Lora"), catalog.matching("lor").map { it.family })
        assertEquals(listOf("Pacifico"), catalog.matching("handwriting").map { it.family })
        assertTrue(catalog.matching("zzz").isEmpty())
    }

    @Test
    fun `it opens the catalog, lists families and downloads the one you pick`() {
        var opened = 0
        val installed = mutableListOf<String>()
        show(GoogleFontsUi(catalog = catalog, onOpen = { opened++ }, onInstall = { installed += it.family }))
        compose.waitForIdle()
        assertEquals(1, opened)
        compose.onNodeWithText("Roboto").assertIsDisplayed()
        compose.onNodeWithText("Serif · 3 styles").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search Google Fonts").performTextInput("paci")
        compose.onNodeWithText("Download").performClick()
        assertEquals(listOf("Pacifico"), installed)
        assertTrue(compose.onAllNodesWithText("Roboto").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `installed families offer remove and downloading ones show progress`() {
        val removed = mutableListOf<String>()
        show(GoogleFontsUi(catalog = catalog, installed = setOf("Roboto"), downloading = mapOf("Lora" to 0.5f), onRemove = { removed += it }))
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("Roboto"), removed)
    }

    @Test
    fun `a failed load says so and offers a retry`() {
        var opened = 0
        show(GoogleFontsUi(catalog = null, error = "Couldn't reach Google Fonts. Check the connection and try again.", onOpen = { opened++ }))
        compose.onNodeWithText("Couldn't reach Google Fonts. Check the connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(2, opened)
    }
}
