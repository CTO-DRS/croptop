package app.croptop.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.croptop.core.data.catalog.CatalogRepository
import app.croptop.core.model.CatalogImage
import app.croptop.core.model.ColorLabel
import app.croptop.core.model.Flag
import app.croptop.core.model.LibraryCollection
import app.croptop.core.model.LibraryFilter
import app.croptop.core.model.LibraryFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(private val catalog: CatalogRepository) : ViewModel() {
    val folders: StateFlow<List<LibraryFolder>?> = catalog.folders.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _filter = MutableStateFlow(LibraryFilter())
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    /** Ordered ids of the filtered photos; null until the first query returns. Rows load by page ([paged]). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ids: StateFlow<List<Long>?> = _filter.flatMapLatest { catalog.ids(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Grid rows, loaded a page at a time for large catalogs. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val paged: Flow<PagingData<CatalogImage>> = _filter.flatMapLatest { catalog.pagedImages(it) }.cachedIn(viewModelScope)

    private fun idList() = ids.value.orEmpty()

    /** Grid rows for a handful of ids (Compare/Survey). */
    fun images(ids: List<Long>): Flow<List<CatalogImage>> = catalog.images(ids)

    /** Opens Develop on [uri] with a filmstrip of the photos around the focus (not the whole catalog). */
    fun develop(uri: String, open: (String, List<String>) -> Unit) = viewModelScope.launch {
        val list = idList()
        val i = list.indexOf(_focus.value).coerceAtLeast(0)
        val window = list.subList((i - FILMSTRIP_RADIUS).coerceAtLeast(0), (i + FILMSTRIP_RADIUS + 1).coerceAtMost(list.size))
        open(uri, catalog.uris(window))
    }

    /** Selected ids; [focus] is the "current" photo for the loupe and metadata panel. */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()
    private val _focus = MutableStateFlow<Long?>(null)
    val focus: StateFlow<Long?> = _focus.asStateFlow()

    /** Full EXIF/IPTC for the focused photo; the grid list only carries what the grid draws. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val focusedDetail: StateFlow<CatalogImage?> = _focus
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(null) else catalog.image(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _scanning = MutableStateFlow<String?>(null)
    val scanning: StateFlow<String?> = _scanning.asStateFlow()

    /** Culling: move to the next photo after rating/flagging. */
    val autoAdvance = MutableStateFlow(true)

    val collections: StateFlow<List<LibraryCollection>> = catalog.collections.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Open collection name dialog (new / rename); null when closed. */
    val nameRequest = MutableStateFlow<NameRequest?>(null)

    /** The smart collection being browsed (its criteria are in [filter]); null otherwise. */
    private val _activeSmart = MutableStateFlow<Long?>(null)
    val activeSmart: StateFlow<Long?> = _activeSmart.asStateFlow()

    init {
        // SAF trees can't be watched, so check every folder for added/changed/removed files on open.
        viewModelScope.launch { rescanNow("Checking folders for new photos…") }
    }

    /** Sidebar: All Photos (null), a folder, or a collection. */
    fun showAll() = showSource { it.copy(folderUri = null, collectionId = null) }

    fun showFolder(treeUri: String) = showSource { it.copy(folderUri = treeUri, collectionId = null) }

    fun showCollection(c: LibraryCollection) {
        val smart = c.smart
        if (smart != null) {
            _filter.update { smart.copy(sort = it.sort) }
            _activeSmart.value = c.id
        } else {
            showSource { it.copy(folderUri = null, collectionId = c.id) }
        }
    }

    private fun showSource(f: (LibraryFilter) -> LibraryFilter) {
        _activeSmart.value = null
        _filter.update(f)
    }

    /** New manual collection; the current selection goes into it. */
    fun createCollection(name: String) = viewModelScope.launch {
        val id = catalog.createCollection(name)
        val ids = targets()
        if (ids.isNotEmpty()) catalog.addToCollection(id, ids)
    }

    /** New smart collection from the current filters. */
    fun createSmartCollection(name: String) = viewModelScope.launch {
        val id = catalog.createCollection(name, smart = _filter.value.criteria())
        _activeSmart.value = id
    }

    fun renameCollection(id: Long, name: String) = viewModelScope.launch { catalog.renameCollection(id, name) }

    fun deleteCollection(id: Long) = viewModelScope.launch {
        catalog.deleteCollection(id)
        if (_filter.value.collectionId == id || _activeSmart.value == id) showAll()
    }

    fun addSelectionTo(collectionId: Long) = viewModelScope.launch { catalog.addToCollection(collectionId, targets()) }

    /** Removes the selection from the manual collection being browsed. */
    fun removeSelectionFromCollection() = viewModelScope.launch {
        val id = _filter.value.collectionId ?: return@launch
        catalog.removeFromCollection(id, targets())
        _selection.value = emptySet()
    }

    fun rescan(treeUri: String? = null) = viewModelScope.launch { rescanNow("Checking for changes…", treeUri) }

    private suspend fun rescanNow(message: String, only: String? = null) {
        val list = catalog.folders.first().filter { only == null || it.treeUri == only }
        if (list.isNotEmpty()) {
            _scanning.value = message
            list.forEach { f -> runCatching { catalog.addFolder(f.treeUri, f.name) } }
        }
        _scanning.value = if (list.isNotEmpty()) "Reading photo details…" else null
        backfillNow()
        _scanning.value = null
    }

    fun setFilter(transform: (LibraryFilter) -> LibraryFilter) = _filter.update(transform)

    fun addFolder(treeUri: String, name: String) = viewModelScope.launch {
        _scanning.value = "Adding “$name”…"
        val n = runCatching { catalog.addFolder(treeUri, name) }.getOrDefault(0)
        _scanning.value = "Reading photo details… ($n photos)"
        backfillNow()
        _scanning.value = null
    }

    fun removeFolder(treeUri: String) = viewModelScope.launch {
        catalog.removeFolder(treeUri)
        if (_filter.value.folderUri == treeUri) showAll()
    }

    private suspend fun backfillNow() {
        do {
            val updated = catalog.backfillMetadata(64)
        } while (updated > 0)
    }

    // ---- Selection ----

    fun click(id: Long, additive: Boolean, range: Boolean) {
        val list = idList()
        _selection.value = when {
            range && _focus.value != null -> {
                val a = list.indexOf(_focus.value)
                val b = list.indexOf(id)
                if (a < 0 || b < 0) setOf(id) else list.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
            }

            additive -> if (id in _selection.value) _selection.value - id else _selection.value + id

            else -> setOf(id)
        }
        _focus.value = id
    }

    fun selectAll() {
        _selection.value = idList().toSet()
    }

    fun move(delta: Int) {
        val list = idList()
        if (list.isEmpty()) return
        val i = list.indexOf(_focus.value)
        val next = list[(if (i < 0) 0 else i + delta).coerceIn(0, list.lastIndex)]
        _focus.value = next
        _selection.value = setOf(next)
    }

    /** Compare/Survey: ratings and flags go to the active frame only and never auto-advance. */
    val pinnedTarget = MutableStateFlow<Long?>(null)

    private fun targets(): List<Long> = pinnedTarget.value?.let { listOf(it) } ?: _selection.value.ifEmpty { setOfNotNull(_focus.value) }.toList()

    private fun afterMark() {
        if (pinnedTarget.value == null && autoAdvance.value && _selection.value.size <= 1) move(1)
    }

    /** Moves focus without touching the selection (Compare/Survey). */
    fun focusOn(id: Long) {
        _focus.value = id
    }

    /** Removes one photo from the selection (Survey's ✕). */
    fun deselect(id: Long) {
        _selection.value = _selection.value - id
    }

    fun rate(stars: Int) = viewModelScope.launch {
        catalog.setRating(targets(), stars)
        afterMark()
    }

    fun flag(f: Flag) = viewModelScope.launch {
        catalog.setFlag(targets(), f)
        afterMark()
    }

    fun label(l: ColorLabel) = viewModelScope.launch {
        val t = targets()
        // Pressing the same label again clears it (Lightroom behavior).
        val current = t.firstOrNull()?.let { catalog.images(listOf(it)).first().firstOrNull()?.label }
        catalog.setLabel(t, if (current == l) ColorLabel.NONE else l)
    }

    fun addKeywords(text: String) = viewModelScope.launch {
        catalog.addKeywords(targets(), text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }

    fun keywords(id: Long) = catalog.keywords(id)

    fun setIptc(edit: app.croptop.core.model.IptcEdit) = viewModelScope.launch { catalog.setIptc(targets(), edit) }

    private companion object {
        const val FILMSTRIP_RADIUS = 100
    }
}
