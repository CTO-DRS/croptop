package app.croptop.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeMenu
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.LibraryCollection
import app.croptop.core.model.LibraryFilter
import app.croptop.core.model.LibraryFolder

/** What the collection name dialog is for. */
sealed interface NameRequest {
    data class New(val smart: Boolean) : NameRequest

    data class Rename(val collection: LibraryCollection) : NameRequest
}

/** Left column: All Photos, folders (rescan/remove), collections (new/rename/delete). */
@Composable
internal fun LibrarySidebar(
    folders: List<LibraryFolder>,
    collections: List<LibraryCollection>,
    filter: LibraryFilter,
    activeSmart: Long?,
    visibleCount: Int,
    onAll: () -> Unit,
    onFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRescan: (String) -> Unit,
    onCollection: (LibraryCollection) -> Unit,
    onNameDialog: (NameRequest) -> Unit,
    onDeleteCollection: (Long) -> Unit,
) {
    val c = Ae.colors
    val allSelected = filter.folderUri == null && filter.collectionId == null && activeSmart == null
    Column(Modifier.width(220.dp).fillMaxHeight().background(c.surface1).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SidebarRow("All Photos", if (allSelected) visibleCount else null, allSelected, icon = AeIcons.Image, onClick = onAll)
        SectionHeader("FOLDERS")
        folders.forEach { f ->
            AeContextMenuArea(
                entries = {
                    listOf(
                        AeMenuEntry.Item("Check for New Photos", { onRescan(f.treeUri) }),
                        AeMenuEntry.Divider,
                        AeMenuEntry.Item("Remove from Library", { onRemoveFolder(f.treeUri) }, icon = AeIcons.Close),
                    )
                },
            ) {
                SidebarRow(f.name, f.imageCount, filter.folderUri == f.treeUri && activeSmart == null, icon = AeIcons.Open) { onFolder(f.treeUri) }
            }
        }
        var menu by remember { mutableStateOf(false) }
        SectionHeader("COLLECTIONS") {
            Box {
                AeIconButton(AeIcons.Add, "New collection", { menu = true })
                AeMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    entries = listOf(
                        AeMenuEntry.Item("New Collection…", { onNameDialog(NameRequest.New(smart = false)) }),
                        AeMenuEntry.Item(
                            "New Smart Collection from Filters…",
                            { onNameDialog(NameRequest.New(smart = true)) },
                            enabled = filter.isActive,
                        ),
                    ),
                )
            }
        }
        if (collections.isEmpty()) {
            AeText(
                "Group photos across folders. Smart collections keep your filters.",
                style = Ae.type.caption,
                color = c.textTertiary,
                maxLines = 3,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        collections.forEach { col ->
            AeContextMenuArea(
                entries = {
                    listOf(
                        AeMenuEntry.Item("Rename…", { onNameDialog(NameRequest.Rename(col)) }),
                        AeMenuEntry.Item("Delete Collection", { onDeleteCollection(col.id) }, icon = AeIcons.Delete),
                    )
                },
            ) {
                val selected = if (col.isSmart) activeSmart == col.id else filter.collectionId == col.id
                SidebarRow(col.name, col.imageCount, selected, icon = if (col.isSmart) AeIcons.Sparkle else AeIcons.Library) { onCollection(col) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AeText(title, style = Ae.type.panelHeader, color = Ae.colors.textTertiary, modifier = Modifier.weight(1f))
        action()
    }
}

/** Metadata panel: add the selection to a collection, or take it out of the one being browsed. */
@Composable
internal fun CollectionActions(vm: LibraryViewModel, selectedCount: Int) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val manual = collections.filter { !it.isSmart }
    val current = manual.firstOrNull { it.id == filter.collectionId }
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Box {
            AeButton(if (selectedCount > 1) "Add $selectedCount to Collection" else "Add to Collection", { menu = true }, icon = AeIcons.Add)
            AeMenu(
                expanded = menu,
                onDismiss = { menu = false },
                entries = buildList {
                    manual.forEach { col -> add(AeMenuEntry.Item(col.name, { vm.addSelectionTo(col.id) })) }
                    if (manual.isNotEmpty()) add(AeMenuEntry.Divider)
                    add(AeMenuEntry.Item("New Collection…", { vm.nameRequest.value = NameRequest.New(smart = false) }, icon = AeIcons.Add))
                },
            )
        }
        if (current != null) AeButton("Remove from “${current.name}”", vm::removeSelectionFromCollection, style = AeButtonStyle.Ghost)
    }
}

@Composable
internal fun CollectionNameDialog(req: NameRequest, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember {
        mutableStateOf(
            when (req) {
                is NameRequest.Rename -> req.collection.name
                is NameRequest.New -> ""
            },
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val valid = name.isNotBlank()
    fun done() = if (valid) onDone(name.trim()) else Unit
    val c = Ae.colors
    AeDialog(
        title = when (req) {
            is NameRequest.Rename -> "Rename Collection"
            is NameRequest.New -> if (req.smart) "New Smart Collection" else "New Collection"
        },
        body = (req as? NameRequest.New)?.let {
            if (it.smart) "Saves the current filters. Matching photos appear automatically." else "The selected photos are added to it."
        },
        onDismiss = onCancel,
        onConfirm = ::done,
        width = 400.dp,
        actions = {
            AeButton("Cancel", onCancel)
            AeButton(if (req is NameRequest.Rename) "Rename" else "Create", ::done, style = AeButtonStyle.Primary, enabled = valid)
        },
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            singleLine = true,
            textStyle = Ae.type.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(Ae.density.controlHeight)
                .background(c.surface3, Ae.shapes.control)
                .border(1.dp, c.accent, Ae.shapes.control)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "Collection name" },
        )
    }
}
