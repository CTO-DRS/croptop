package app.croptop.feature.editor.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.FontRepository
import app.croptop.core.data.GoogleFontListing
import app.croptop.core.data.GoogleFonts
import app.croptop.core.model.CharacterStyle
import app.croptop.core.model.FontFamily
import app.croptop.core.model.ParagraphStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the text tool's options bar and the Character/Paragraph panels show. Hilt-free so
 * screenshot tests can pass fixed fonts; [TypeViewModel.ui] builds the live one.
 */
data class TypeUi(
    val families: List<FontFamily>?,
    val favorites: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val character: CharacterStyle = CharacterStyle(),
    val paragraph: ParagraphStyle = ParagraphStyle(),
    val onCharacter: (CharacterStyle) -> Unit = {},
    val onParagraph: (ParagraphStyle) -> Unit = {},
    val onToggleFavorite: (String) -> Unit = {},
    val google: GoogleFontsUi? = null,
) {
    val family: FontFamily? get() = families?.firstOrNull { it.name == character.family }

    /** Picks [family], keeping the current style when the family has it; resets axes it doesn't have. */
    fun chooseFamily(family: FontFamily) {
        val face = family.face(character.style)
        val tags = face.axes.map { it.tag }.toSet()
        onCharacter(character.copy(family = family.name, style = face.style, axes = character.axes.filterKeys { it in tags }))
    }
}

/** What the "Get more fonts" dialog shows: the catalog (null while loading), what is installed or downloading, and any error. */
data class GoogleFontsUi(
    val catalog: List<GoogleFontListing>? = null,
    val installed: Set<String> = emptySet(),
    /** Family → download progress 0–1. */
    val downloading: Map<String, Float> = emptyMap(),
    val error: String? = null,
    val onOpen: () -> Unit = {},
    val onInstall: (GoogleFontListing) -> Unit = {},
    val onRemove: (String) -> Unit = {},
)

/**
 * Text tool settings. Until the text engine lands these are the tool's defaults
 * for new text layers; afterwards they also mirror and edit the selected text layer.
 */
@HiltViewModel
class TypeViewModel @Inject constructor(private val fonts: FontRepository, private val google: GoogleFonts) : ViewModel() {
    val character = MutableStateFlow(CharacterStyle())
    val paragraph = MutableStateFlow(ParagraphStyle())

    private val googleCatalog = MutableStateFlow<List<GoogleFontListing>?>(null)
    private val googleDownloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val googleError = MutableStateFlow<String?>(null)
    private val googleUi: StateFlow<GoogleFontsUi> = combine(googleCatalog, google.installed, googleDownloading, googleError) { catalog, installed, busy, error ->
        GoogleFontsUi(catalog, installed, busy, error, ::openGoogleFonts, ::installGoogleFont, ::removeGoogleFont)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoogleFontsUi(onOpen = ::openGoogleFonts))

    /** Loads the catalog the first time the dialog opens (and again after a failure). */
    fun openGoogleFonts() {
        if (googleCatalog.value != null) return
        googleError.value = null
        viewModelScope.launch {
            runCatching { google.catalog() }
                .onSuccess { googleCatalog.value = it }
                .onFailure { googleError.value = "Couldn't reach Google Fonts. Check the connection and try again." }
        }
    }

    fun installGoogleFont(listing: GoogleFontListing) {
        if (listing.family in googleDownloading.value) return
        googleError.value = null
        googleDownloading.update { it + (listing.family to 0f) }
        viewModelScope.launch {
            runCatching { google.install(listing) { p -> googleDownloading.update { it + (listing.family to p) } } }
                .onFailure { googleError.value = "${listing.family} couldn't be downloaded. Check the connection and try again." }
            googleDownloading.update { it - listing.family }
        }
    }

    fun removeGoogleFont(family: String) = google.uninstall(family)

    private val lists = combine(fonts.families, fonts.favorites, fonts.recent) { f, fav, r -> Triple(f, fav, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ui: StateFlow<TypeUi> = combine(lists, character, paragraph, googleUi) { l, c, p, g ->
        TypeUi(
            families = l?.first,
            favorites = l?.second.orEmpty(),
            recent = l?.third.orEmpty(),
            character = c,
            paragraph = p,
            onCharacter = ::setCharacter,
            onParagraph = ::setParagraph,
            onToggleFavorite = ::toggleFavorite,
            google = g,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TypeUi(families = null, onCharacter = ::setCharacter, onParagraph = ::setParagraph),
    )

    fun setCharacter(style: CharacterStyle) {
        val before = character.value.family
        character.value = style.normalized()
        if (style.family != before) viewModelScope.launch { fonts.markUsed(style.family) }
    }

    fun setParagraph(style: ParagraphStyle) = paragraph.update { style.normalized() }

    fun toggleFavorite(family: String) {
        viewModelScope.launch { fonts.toggleFavorite(family) }
    }
}
