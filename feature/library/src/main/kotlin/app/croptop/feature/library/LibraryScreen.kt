package app.croptop.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.croptop.core.designsystem.component.AeAppHeader
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeEmptyState
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.SearchCommandsButton
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.ActionScope
import app.croptop.core.input.ShortcutScope
import app.croptop.core.model.CatalogImage
import app.croptop.core.model.ColorLabel
import app.croptop.core.model.Flag
import app.croptop.core.model.LibraryFilter
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute : NavKey

/** Library (§6.4): folders, filter bar, grid, metadata, culling. */
@Composable
fun LibraryScreen(
    onOpenInEditor: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** RAW only: open as flattened pixels instead of a re-editable smart object. */
    onOpenFlattened: ((String) -> Unit)? = null,
    onDevelop: (String, List<String>) -> Unit = { _, _ -> },
    vm: LibraryViewModel = hiltViewModel(),
    batchVm: app.croptop.feature.library.batch.BatchExportViewModel = hiltViewModel(),
) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val ids by vm.ids.collectAsStateWithLifecycle()
    val paged = vm.paged.collectAsLazyPagingItems()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val focusId by vm.focus.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val autoAdvance by vm.autoAdvance.collectAsStateWithLifecycle()
    val develop: (String) -> Unit = { uri -> vm.develop(uri, onDevelop) }
    val batchState by batchVm.state.collectAsStateWithLifecycle()
    var batchDialog by rememberSaveable { mutableStateOf(false) }
    val userPresets by batchVm.userPresets.collectAsStateWithLifecycle()
    var cellDp by rememberSaveable { mutableFloatStateOf(160f) }
    var loupe by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(LibraryView.GRID) }
    var compareSelect by rememberSaveable { mutableStateOf<Long?>(null) }
    var compareCandidate by rememberSaveable { mutableStateOf<Long?>(null) }
    val nameDialog by vm.nameRequest.collectAsStateWithLifecycle()
    nameDialog?.let { req ->
        CollectionNameDialog(req, onDone = { name ->
            when (req) {
                is NameRequest.New -> if (req.smart) vm.createSmartCollection(name) else vm.createCollection(name)
                is NameRequest.Rename -> vm.renameCollection(req.collection.id, name)
            }
            vm.nameRequest.value = null
        }, onCancel = { vm.nameRequest.value = null })
    }
    val list = ids.orEmpty()
    val surveyIds = remember(list, selection) { list.filter { it in selection } }
    val surveyed by remember(surveyIds) { vm.images(surveyIds) }.collectAsStateWithLifecycle(emptyList())

    /** Next photo after [from] in the filtered list, skipping [skip], wrapping around. */
    fun step(from: Long?, delta: Int, skip: Long? = null): Long? {
        val pool = list.filter { it != skip }
        if (pool.isEmpty()) return null
        val i = pool.indexOf(from)
        return pool[((if (i < 0) 0 else i + delta) % pool.size + pool.size) % pool.size]
    }

    fun switchTo(v: LibraryView) {
        when (v) {
            LibraryView.COMPARE -> {
                val sel = selection.toList()
                compareSelect = if (sel.size >= 2) sel[0] else focusId ?: list.firstOrNull()
                compareCandidate = if (sel.size >= 2) sel[1] else step(compareSelect, 1, skip = compareSelect)
                compareCandidate?.let(vm::focusOn)
            }

            LibraryView.SURVEY -> surveyIds.firstOrNull { it == focusId } ?: surveyIds.firstOrNull()?.let(vm::focusOn)

            LibraryView.GRID -> Unit
        }
        loupe = false
        view = v
    }
    // Compare/Survey mark only the active frame, without auto-advance.
    LaunchedEffect(view, focusId) { vm.pinnedTarget.value = if (view == LibraryView.GRID) null else focusId }
    val context = LocalContext.current
    val detail by vm.focusedDetail.collectAsStateWithLifecycle()
    // Keeps the previous photo for the frame its replacement takes to load, instead of flashing empty.
    val focused = detail.takeIf { focusId != null }

    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = android.net.Uri.decode(uri.lastPathSegment ?: "Folder").substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
            vm.addFolder(uri.toString(), name)
        }
    }

    ActionScope(ShortcutScope.LIBRARY) {
        on("library.pick") { vm.flag(Flag.PICK) }
        on("library.reject") { vm.flag(Flag.REJECT) }
        on("library.unflag") { vm.flag(Flag.NONE) }
        (0..5).forEach { n -> on("library.rate$n") { vm.rate(n) } }
        on("library.labelRed") { vm.label(ColorLabel.RED) }
        on("library.labelYellow") { vm.label(ColorLabel.YELLOW) }
        on("library.labelGreen") { vm.label(ColorLabel.GREEN) }
        on("library.labelBlue") { vm.label(ColorLabel.BLUE) }
    }

    val c = Ae.colors
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { keys.requestFocus() }
    var columns by remember { mutableStateOf(4) }
    Column(
        modifier
            .fillMaxSize()
            .background(c.app)
            .focusRequester(keys)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    view == LibraryView.COMPARE && (e.key == Key.DirectionRight || e.key == Key.DirectionLeft) -> {
                        compareCandidate = step(compareCandidate, if (e.key == Key.DirectionRight) 1 else -1, skip = compareSelect)
                        compareCandidate?.let(vm::focusOn)
                        true
                    }

                    view == LibraryView.COMPARE && e.key == Key.DirectionUp -> {
                        compareSelect = compareCandidate ?: compareSelect
                        compareCandidate = step(compareSelect, 1, skip = compareSelect)
                        compareCandidate?.let(vm::focusOn)
                        true
                    }

                    view == LibraryView.SURVEY && (e.key == Key.DirectionRight || e.key == Key.DirectionLeft) -> {
                        val i = surveyIds.indexOf(focusId)
                        if (surveyIds.isNotEmpty()) {
                            val d = if (e.key == Key.DirectionRight) 1 else -1
                            vm.focusOn(surveyIds[((i + d) % surveyIds.size + surveyIds.size) % surveyIds.size])
                        }
                        true
                    }

                    view != LibraryView.GRID && (e.key == Key.Escape || (e.key == Key.G && !e.isCtrlPressed)) -> {
                        switchTo(LibraryView.GRID)
                        true
                    }

                    e.key == Key.C && !e.isCtrlPressed && list.isNotEmpty() -> {
                        switchTo(LibraryView.COMPARE)
                        true
                    }

                    e.key == Key.N && !e.isCtrlPressed && list.isNotEmpty() -> {
                        switchTo(LibraryView.SURVEY)
                        true
                    }

                    e.key == Key.DirectionRight -> {
                        vm.move(1)
                        true
                    }

                    e.key == Key.DirectionLeft -> {
                        vm.move(-1)
                        true
                    }

                    e.key == Key.DirectionDown && !loupe -> {
                        vm.move(columns)
                        true
                    }

                    e.key == Key.DirectionUp && !loupe -> {
                        vm.move(-columns)
                        true
                    }

                    e.key == Key.Enter || e.key == Key.Spacebar -> {
                        if (focused != null) loupe = !loupe
                        true
                    }

                    e.key == Key.Escape && loupe -> {
                        loupe = false
                        true
                    }

                    e.key == Key.A && e.isCtrlPressed -> {
                        vm.selectAll()
                        true
                    }

                    e.key == Key.E && !e.isCtrlPressed && focused != null -> {
                        onOpenInEditor(focused.uri)
                        true
                    }

                    e.key == Key.D && !e.isCtrlPressed && focused != null -> {
                        develop(focused.uri)
                        true
                    }

                    else -> false
                }
            },
    ) {
        AeAppHeader(startPadding = 8.dp) {
            AeIconButton(AeIcons.Close, "Back", onBack)
            AeText("Library", style = Ae.type.title, modifier = Modifier.weight(1f))
            // An empty library has one action (the empty state's Add Folder); views and a second button would only distract.
            val hasFolders = !folders.isNullOrEmpty()
            if (hasFolders) AeSegmented(LibraryView.entries, view, ::switchTo, { it.label })
            SearchCommandsButton()
            if (hasFolders) {
                AeButton(
                    if (selection.size > 1) "Export ${selection.size}…" else "Export…",
                    { batchDialog = true },
                    icon = AeIcons.Export,
                    enabled = selection.isNotEmpty() || focusId != null,
                )
            }
            if (hasFolders) AeButton("Add Folder…", { addFolder.launch(null) }, icon = AeIcons.Add)
        }
        val exportIds = if (selection.isNotEmpty()) list.filter { it in selection } else listOfNotNull(focusId)
        if (batchDialog && exportIds.isNotEmpty()) {
            app.croptop.feature.library.batch.BatchExportDialog(
                exportIds.size,
                onStart = { options, folder ->
                    batchDialog = false
                    batchVm.start(exportIds, options, folder)
                },
                onDismiss = { batchDialog = false },
                userPresets = userPresets,
                onSavePreset = batchVm::savePreset,
                onDeletePreset = batchVm::deletePreset,
            )
        }
        app.croptop.feature.library.batch.BatchExportStatusDialog(batchState, batchVm::cancel, batchVm::dismiss)
        AeDivider()
        if (folders?.isEmpty() == true) {
            AeEmptyState(
                "Add a folder of photos",
                body = "CropTop reads your photos where they are — nothing is copied. Ratings and flags stay in CropTop.",
                icon = AeIcons.Library,
                modifier = Modifier.fillMaxSize(),
                action = { AeButton("Add Folder…", { addFolder.launch(null) }, style = AeButtonStyle.Primary) },
            )
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val collections by vm.collections.collectAsStateWithLifecycle()
            val activeSmart by vm.activeSmart.collectAsStateWithLifecycle()
            LibrarySidebar(
                folders.orEmpty(),
                collections,
                filter,
                activeSmart,
                list.size,
                onAll = vm::showAll,
                onFolder = vm::showFolder,
                onRemoveFolder = vm::removeFolder,
                onRescan = vm::rescan,
                onCollection = vm::showCollection,
                onNameDialog = { vm.nameRequest.value = it },
                onDeleteCollection = vm::deleteCollection,
            )
            AeDivider(Modifier.fillMaxHeight(), vertical = true)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                FilterBar(filter, onFilter = vm::setFilter)
                AeDivider()
                if (ids == null) {
                    Spacer(Modifier.fillMaxSize()) // first query still running; don't flash "No photos"
                } else if (list.isEmpty()) {
                    AeEmptyState(
                        if (filter.isActive) "No photos match these filters" else "No photos yet",
                        body = if (filter.isActive) null else scanning ?: "This folder has no supported images.",
                        modifier = Modifier.fillMaxSize(),
                        action = if (filter.isActive) {
                            (
                                {
                                    AeButton("Clear Filters", {
                                        vm.setFilter { LibraryFilter(folderUri = it.folderUri, collectionId = it.collectionId, sort = it.sort) }
                                    })
                                }
                                )
                        } else {
                            null
                        },
                    )
                } else {
                    val pair by remember(compareSelect, compareCandidate) {
                        vm.images(listOfNotNull(compareSelect, compareCandidate))
                    }.collectAsStateWithLifecycle(emptyList())
                    val sel = pair.firstOrNull { it.id == compareSelect }
                    when {
                        view == LibraryView.COMPARE && sel != null -> CompareView(
                            sel,
                            pair.firstOrNull { it.id == compareCandidate },
                            activeId = focusId ?: sel.id,
                            onActivate = vm::focusOn,
                        )

                        view == LibraryView.SURVEY -> SurveyView(surveyed, focusId, onActivate = vm::focusOn, onRemove = vm::deselect)

                        else -> PhotoGrid(paged, selection, list.indexOf(focusId), cellDp.dp, onColumns = {
                            columns = it
                        }, onClick = vm::click, onOpen = { loupe = true }, onZoom = {
                            cellDp =
                                (cellDp * it).coerceIn(96f, 360f)
                        })
                    }
                }
            }
            AeDivider(Modifier.fillMaxHeight(), vertical = true)
            MetadataPanel(
                focused,
                vm,
                onOpenInEditor,
                develop,
                onOpenFlattened,
                Modifier.width(280.dp).fillMaxHeight(),
                selectedCount = selection.size,
            )
        }
        AeDivider()
        Row(
            Modifier.fillMaxWidth().height(maxOf(40.dp, Ae.density.rowHeight)).background(c.surface2).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val t = Ae.type.caption
            AeText("${list.size} photos" + if (selection.size > 1) " · ${selection.size} selected" else "", style = t, color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            scanning?.let { AeText(it, style = t, color = c.textSecondary) }
            Spacer(Modifier.width(16.dp))
            AeCheckbox(autoAdvance, { vm.autoAdvance.value = it }, "Auto-advance")
            Spacer(Modifier.width(16.dp))
            AeInlineSlider(null, cellDp, {
                cellDp = it
            }, range = 96f..360f, defaultValue = 160f, trackWidth = 96.dp, showValueField = false, contentDescription = "Thumbnail size")
        }
    }
    if (loupe && focused != null) {
        CullingLoupe(focused, list.indexOf(focused.id) + 1, list.size, onClose = {
            loupe = false
        }, onPrev = { vm.move(-1) }, onNext = { vm.move(1) }, vm = vm)
    }
}

@Composable
internal fun SidebarRow(
    label: String,
    count: Int?,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(Ae.density.rowHeight + 4.dp)
            .background(
                if (selected) {
                    c.accentMuted
                } else if (hovered) {
                    c.surface2
                } else {
                    Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) c.textPrimary else c.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        AeText(label, modifier = Modifier.weight(1f))
        if (count != null) AeText("$count", style = Ae.type.caption, color = c.textTertiary)
    }
}

private enum class FlagFilter(val label: String, val flag: Flag?) {
    ALL(
        "All flags",
        null,
    ),
    PICKS("Picks", Flag.PICK),
    UNFLAGGED("Unflagged", Flag.NONE),
    REJECTS("Rejects", Flag.REJECT),
}

@Composable
private fun FilterBar(filter: LibraryFilter, onFilter: ((LibraryFilter) -> LibraryFilter) -> Unit) {
    val c = Ae.colors
    FlowRow(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 280.dp)
            .verticalScroll(rememberScrollState())
            .background(c.surface1)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.width(
                200.dp,
            ).height(
                Ae.density.controlHeight,
            ).background(c.surface3, Ae.shapes.control).border(1.dp, c.lineHairline, Ae.shapes.control).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (filter.query.isEmpty()) AeText("Search name, camera, keyword", style = Ae.type.caption, color = c.textTertiary)
            BasicTextField(
                filter.query,
                { q -> onFilter { it.copy(query = q) } },
                singleLine = true,
                textStyle = Ae.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().height(Ae.density.controlHeight).semantics { contentDescription = "Search photos" },
            )
        }
        AeDropdown((0..5).toList(), filter.minRating, { r -> onFilter { it.copy(minRating = r) } }, {
            if (it ==
                0
            ) {
                "Any rating"
            } else {
                "★".repeat(it) + " and up"
            }
        })
        AeDropdown(
            FlagFilter.entries,
            FlagFilter.entries.first {
                it.flag == filter.flag
            },
            { f -> onFilter { it.copy(flag = f.flag) } },
            { it.label },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ColorLabel.entries.drop(1).forEach { l ->
                val on = filter.label == l
                Box(
                    Modifier.size(40.dp)
                        .toggleable(value = on, role = Role.Checkbox) { onFilter { it.copy(label = if (on) null else l) } }
                        .semantics { contentDescription = "Filter ${l.title} label" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(18.dp)
                            .background(Color(l.argb), Ae.shapes.chip)
                            .border(if (on) 2.dp else 1.dp, if (on) c.textPrimary else c.lineStrong, Ae.shapes.chip),
                    )
                }
            }
        }
        AeCheckbox(filter.rawOnly, { r -> onFilter { it.copy(rawOnly = r) } }, "RAW only")
        AeDropdown(app.croptop.core.model.LibrarySort.entries, filter.sort, { s -> onFilter { it.copy(sort = s) } }, { it.label })
    }
}

@Composable
private fun PhotoGrid(
    images: LazyPagingItems<CatalogImage>,
    selection: Set<Long>,
    focusIndex: Int,
    cell: Dp,
    onColumns: (Int) -> Unit,
    onClick: (Long, Boolean, Boolean) -> Unit,
    onOpen: () -> Unit,
    onZoom: (Float) -> Unit,
) {
    val state = rememberLazyGridState()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Ctrl+wheel resizes thumbnails.
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Scroll && ev.keyboardModifiers.isCtrlPressed) {
                            val dy = ev.changes.first().scrollDelta.y
                            onZoom(if (dy < 0) 1.1f else 0.9f)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        val cols = maxOf(1, (maxWidth / (cell + 8.dp)).toInt())
        LaunchedEffect(cols) { onColumns(cols) }
        // Arrow keys can move the focus past the viewport, into pages that aren't loaded yet.
        LaunchedEffect(focusIndex) {
            val info = state.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return@LaunchedEffect
            if (focusIndex < 0) return@LaunchedEffect
            val item = info.visibleItemsInfo.firstOrNull { it.index == focusIndex }
            if (item != null && item.offset.y >= 0 && item.offset.y + item.size.height <= info.viewportEndOffset) return@LaunchedEffect
            val above = item?.let { it.offset.y < 0 } ?: (focusIndex < first.index)
            state.scrollToItem(focusIndex)
            if (!above) {
                // Moving down: land the row at the bottom edge, like a list's arrow-key scrolling.
                val h = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusIndex }?.size?.height ?: 0
                state.scrollBy(-(info.viewportEndOffset - h - info.afterContentPadding).toFloat().coerceAtLeast(0f))
            }
        }
        val px = with(density) { cell.roundToPx() }.coerceAtLeast(96)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(images.itemCount, key = images.itemKey { it.id }) { i ->
                val img = images[i]
                if (img == null) {
                    PlaceholderCell()
                } else {
                    PhotoCell(img, img.id in selection, px, onClick = { add, range -> onClick(img.id, add, range) }, onOpen = onOpen)
                }
            }
        }
    }
}

/** A row that hasn't paged in yet; same footprint as [PhotoCell] so nothing jumps when it loads. */
@Composable
private fun PlaceholderCell() {
    val c = Ae.colors
    Column(
        Modifier
            .background(c.surface1, Ae.shapes.control)
            .border(1.dp, c.lineHairline, Ae.shapes.control)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(c.canvas, Ae.shapes.chip))
        AeText(" ", style = Ae.type.caption, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PhotoCell(img: CatalogImage, selected: Boolean, px: Int, onClick: (Boolean, Boolean) -> Unit, onOpen: () -> Unit) {
    val c = Ae.colors
    val thumb = rememberThumbnail(img.uri, px)
    Column(
        Modifier
            .background(if (selected) c.accentMuted else c.surface1, Ae.shapes.control)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.lineHairline, Ae.shapes.control)
            .semantics {
                this.selected = selected
                contentDescription =
                    "${img.name}, ${img.rating} stars" + if (img.flag == Flag.PICK) {
                        ", pick"
                    } else if (img.flag == Flag.REJECT) {
                        ", rejected"
                    } else {
                        ""
                    }
            }
            .pointerInput(img.id) {
                awaitPointerEventScope {
                    var last = 0L
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type != PointerEventType.Press) continue
                        val t = ev.changes.first().uptimeMillis
                        if (t - last <
                            350
                        ) {
                            onOpen()
                        } else {
                            onClick(ev.keyboardModifiers.isCtrlPressed || ev.keyboardModifiers.isMetaPressed, ev.keyboardModifiers.isShiftPressed)
                        }
                        last = t
                    }
                }
            }
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(c.canvas, Ae.shapes.chip), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(
                    thumb,
                    null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(
                        if (img.flag ==
                            Flag.REJECT
                        ) {
                            Modifier.background(Color.Transparent)
                        } else {
                            Modifier
                        },
                    ),
                )
                if (img.flag == Flag.REJECT) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            } else {
                Icon(AeIcons.Image, null, tint = c.textTertiary, modifier = Modifier.size(24.dp))
            }
            if (img.isRaw) {
                AeText(
                    "RAW",
                    style = Ae.type.micro,
                    color = c.textSecondary,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(c.surface2, Ae.shapes.chip).padding(horizontal = 4.dp),
                )
            }
            when (img.flag) {
                Flag.PICK -> Icon(AeIcons.Check, "Pick", tint = c.success, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                Flag.REJECT -> Icon(AeIcons.Close, "Rejected", tint = c.danger, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                Flag.NONE -> Unit
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (img.label != ColorLabel.NONE) Box(Modifier.size(8.dp).background(Color(img.label.argb), Ae.shapes.chip))
            AeText(
                img.name,
                style = Ae.type.caption,
                color = c.textSecondary,
                modifier = Modifier.weight(1f).padding(
                    start = if (img.label !=
                        ColorLabel.NONE
                    ) {
                        6.dp
                    } else {
                        0.dp
                    },
                ),
            )
            if (img.rating > 0) AeText("★".repeat(img.rating), style = Ae.type.micro, color = c.warning)
        }
    }
}

private fun CatalogImage.exposureLine(): String = listOfNotNull(
    exposure,
    aperture?.let { "f/${"%.1f".format(it).removeSuffix(".0")}" },
    iso?.let { "ISO $it" },
    focalLength?.let { "${it.toInt()} mm" },
).joinToString(" · ")

@Composable
private fun MetadataPanel(
    img: CatalogImage?,
    vm: LibraryViewModel,
    onOpenInEditor: (String) -> Unit,
    onDevelop: (String) -> Unit,
    onOpenFlattened: ((String) -> Unit)?,
    modifier: Modifier,
    selectedCount: Int = 1,
) {
    val c = Ae.colors
    Column(modifier.background(c.surface1).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (img == null) {
            AeText("Select a photo to see its details.", color = c.textTertiary, maxLines = 2)
            return@Column
        }
        val thumb = rememberThumbnail(img.uri, 512)
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(c.canvas, Ae.shapes.chip), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        AeText(img.name, style = Ae.type.title, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeButton("Develop", { onDevelop(img.uri) }, modifier = Modifier.weight(1f))
            AeButton("Open in Editor", { onOpenInEditor(img.uri) }, style = AeButtonStyle.Primary, modifier = Modifier.weight(1f))
        }
        if (onOpenFlattened != null && app.croptop.core.model.isRawFileName(img.name)) {
            AeButton("Open Flattened", { onOpenFlattened(img.uri) }, style = AeButtonStyle.Ghost, modifier = Modifier.fillMaxWidth())
        }
        StarRow(img.rating) { vm.rate(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AeButton("Pick", {
                vm.flag(
                    if (img.flag ==
                        Flag.PICK
                    ) {
                        Flag.NONE
                    } else {
                        Flag.PICK
                    },
                )
            }, style = if (img.flag == Flag.PICK) AeButtonStyle.Primary else AeButtonStyle.Secondary)
            AeButton("Reject", {
                vm.flag(
                    if (img.flag ==
                        Flag.REJECT
                    ) {
                        Flag.NONE
                    } else {
                        Flag.REJECT
                    },
                )
            }, style = if (img.flag == Flag.REJECT) AeButtonStyle.Danger else AeButtonStyle.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorLabel.entries.drop(1).forEach { l ->
                Box(
                    Modifier
                        .size(20.dp)
                        .background(Color(l.argb), Ae.shapes.chip)
                        .border(if (img.label == l) 2.dp else 1.dp, if (img.label == l) c.textPrimary else c.lineStrong, Ae.shapes.chip)
                        .clickable { vm.label(l) }
                        .semantics { contentDescription = "${l.title} label" },
                )
            }
        }
        AeDivider()
        Detail(
            "Size",
            listOfNotNull(
                if (img.width >
                    0
                ) {
                    "${img.width} × ${img.height}"
                } else {
                    null
                },
                "%.1f MB".format(img.sizeBytes / 1_048_576f),
            ).joinToString(" · "),
        )
        img.takenAt?.let { Detail("Taken", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
        img.camera?.let { Detail("Camera", it) }
        img.lens?.let { Detail("Lens", it) }
        img.exposureLine().takeIf { it.isNotBlank() }?.let { Detail("Exposure", it) }
        KeywordsEditor(img.id, vm)
        CollectionActions(vm, selectedCount)
        AeDivider()
        IptcEditor(img, selectedCount, vm)
    }
}

/** IPTC core fields; edits apply to every selected photo (commit on Enter or leaving the field). */
@Composable
private fun IptcEditor(img: CatalogImage, selectedCount: Int, vm: LibraryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeText("IPTC", style = Ae.type.micro, color = Ae.colors.textTertiary)
        if (selectedCount > 1) AeText("Changes apply to $selectedCount photos", style = Ae.type.caption, color = Ae.colors.textSecondary)
        IptcField("Title", img.title, img.id) { vm.setIptc(app.croptop.core.model.IptcEdit(title = it)) }
        IptcField("Caption", img.caption, img.id, singleLine = false) { vm.setIptc(app.croptop.core.model.IptcEdit(caption = it)) }
        IptcField("Creator", img.creator, img.id) { vm.setIptc(app.croptop.core.model.IptcEdit(creator = it)) }
        IptcField("Copyright", img.copyright, img.id) { vm.setIptc(app.croptop.core.model.IptcEdit(copyright = it)) }
    }
}

@Composable
private fun IptcField(label: String, value: String?, key: Long, singleLine: Boolean = true, onCommit: (String) -> Unit) {
    val c = Ae.colors
    var text by remember(key, value) { mutableStateOf(value.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        if (text.trim() != value.orEmpty()) onCommit(text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AeText(label, style = Ae.type.caption, color = c.textSecondary)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Ae.density.controlHeight)
                .background(c.surface3, Ae.shapes.control)
                .border(1.dp, if (focused) c.accent else Color.Transparent, Ae.shapes.control)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                text,
                { text = it },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                textStyle = Ae.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (focused && !it.isFocused) commit()
                        focused = it.isFocused
                    }
                    .semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        AeText(label.uppercase(), style = Ae.type.micro, color = Ae.colors.textTertiary)
        AeText(value, maxLines = 2)
    }
}

@Composable
private fun StarRow(rating: Int, onRate: (Int) -> Unit) {
    val c = Ae.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..5).forEach { n ->
            AeText(
                "★",
                style = Ae.type.title,
                color = if (n <= rating) c.warning else c.surface4,
                modifier = Modifier
                    .clickable { onRate(if (rating == n) 0 else n) }
                    .semantics { contentDescription = "$n stars" }
                    .padding(2.dp),
            )
        }
    }
}

@Composable
private fun KeywordsEditor(id: Long, vm: LibraryViewModel) {
    val c = Ae.colors
    val keywords by vm.keywords(id).collectAsStateWithLifecycle(emptyList())
    var text by remember(id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AeText("KEYWORDS", style = Ae.type.micro, color = c.textTertiary)
        if (keywords.isNotEmpty()) AeText(keywords.joinToString(", "), maxLines = 3)
        Box(
            Modifier.fillMaxWidth().height(Ae.density.controlHeight).background(c.surface3, Ae.shapes.control).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) AeText("Add keywords, comma separated", style = Ae.type.caption, color = c.textTertiary)
            BasicTextField(
                text,
                { text = it },
                singleLine = true,
                textStyle = Ae.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    vm.addKeywords(text)
                    text = ""
                }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add keywords" },
            )
        }
    }
}

/** Full-screen culling loupe: arrows move, P/X/U and 0–5 rate (auto-advance), Esc closes. */
@Composable
private fun CullingLoupe(
    img: CatalogImage,
    index: Int,
    total: Int,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    vm: LibraryViewModel,
) {
    val c = Ae.colors
    val big = rememberThumbnail(img.uri, 2048)
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AeIconButton(AeIcons.Close, "Close loupe", onClose, shortcut = "Esc")
            AeText(img.name, style = Ae.type.label)
            AeText("$index of $total", style = Ae.type.caption, color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            AeText("P pick · X reject · 0–5 rate · ← → move", style = Ae.type.caption, color = c.textTertiary)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(img.id) {
                    detectTapGestures(onTap = { o ->
                        if (o.x <
                            size.width / 3
                        ) {
                            onPrev()
                        } else if (o.x > size.width * 2 / 3) {
                            onNext()
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            if (big != null) Image(big, img.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StarRow(img.rating) { vm.rate(it) }
            AeText(
                when (img.flag) {
                    Flag.PICK -> "Picked"
                    Flag.REJECT -> "Rejected"
                    Flag.NONE -> ""
                },
                style = Ae.type.label,
                color = if (img.flag == Flag.REJECT) c.danger else c.success,
            )
        }
    }
}
