package app.croptop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeSizes
import app.croptop.core.model.HistoryState

/** Named snapshots: restore, rename, delete, create. */
data class SnapshotActions(
    val onCreate: () -> Unit = {},
    val onRestore: (Int) -> Unit = {},
    /** Pro-labeled placeholder until document duplication from a snapshot is implemented. */
    val onNewDocumentFromState: (Int) -> Unit = {},
    /** Returns false when the engine rejected the name (the dialog stays open to retry). */
    val onRename: (Int, String) -> Boolean = { _, _ -> true },
    val onDelete: (Int) -> Unit = {},
    val onActionCompleted: (String) -> Unit = {},
)

/**
 * History panel: named snapshots on top (click to restore as a new step), then the steps;
 * click a step to jump; undone steps are dimmed until overwritten.
 */
@Composable
fun HistoryPanelContent(history: HistoryState, onJump: (Int) -> Unit, snapshots: SnapshotActions = SnapshotActions()) {
    val c = Ae.colors
    var renaming by remember { mutableStateOf<Int?>(null) }
    history.snapshots.forEach { snap ->
        app.croptop.core.designsystem.component.AeContextMenuArea(
            entries = {
                listOf(
                    app.croptop.core.designsystem.component.AeMenuEntry.Item("Restore", { snapshots.onRestore(snap.index) }),
                    app.croptop.core.designsystem.component.AeMenuEntry.Item("Rename…", {
                        renaming = snap.index
                        snapshots.onActionCompleted("Open Rename Snapshot")
                    }),
                    app.croptop.core.designsystem.component.AeMenuEntry.Divider,
                    app.croptop.core.designsystem.component.AeMenuEntry.Item("Delete Snapshot", {
                        snapshots.onDelete(snap.index)
                    }, icon = AeIcons.Delete),
                )
            },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Ae.density.rowHeight)
                    .clickable { snapshots.onRestore(snap.index) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(AeIcons.Image, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                AeText(snap.name, modifier = Modifier.weight(1f))
            }
        }
    }
    if (history.snapshots.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineHairline))
    val list = rememberLazyListState()
    LaunchedEffect(history.current, history.entries.size) { list.animateScrollToItem(history.current) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp), state = list) {
        itemsIndexed(history.entries) { i, entry ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val current = i == history.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Ae.density.rowHeight)
                    .background(
                        if (current) {
                            c.accentMuted
                        } else if (hovered) {
                            c.surface2
                        } else {
                            Color.Transparent
                        },
                    )
                    .hoverable(interaction)
                    .clickable(interaction, indication = null) { onJump(i) }
                    .semantics {
                        selected = current
                        val state = when {
                            current -> "current"
                            i > history.current -> "undone"
                            else -> "available"
                        }
                        contentDescription = "${entry.label}, history step ${i + 1} of ${history.entries.size}, $state"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(AeSizes.selectedBar).fillMaxHeight().background(if (current) c.accent else Color.Transparent))
                AeText(
                    entry.label,
                    color = if (i > history.current) c.textTertiary else c.textPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
        AeIconButton(AeIcons.Add, "New snapshot", {
            snapshots.onCreate()
        })
    }
    renaming?.let { index ->
        val current = history.snapshots.firstOrNull { it.index == index }?.name.orEmpty()
        SnapshotRenameDialog(
            snapshotIndex = index,
            initial = current,
            onRename = { snapshots.onRename(index, it) },
            onRenameAccepted = { renaming = null },
            onCancel = {
                renaming = null
                snapshots.onActionCompleted("Cancel Rename Snapshot")
            },
        )
    }
}

/** Next free "Snapshot N" name (Photoshop numbering). */
fun nextSnapshotName(history: HistoryState): String {
    val used = history.snapshots.map { it.name.lowercase() }.toSet()
    return generateSequence(1) { it + 1 }.map { "Snapshot $it" }.first { it.lowercase() !in used }
}

@Composable
internal fun SnapshotRenameDialog(
    snapshotIndex: Int,
    initial: String,
    onRename: (String) -> Boolean,
    onRenameAccepted: () -> Unit,
    onCancel: () -> Unit,
) {
    // A new rename target starts with its own name even when the dialog stays composed.
    androidx.compose.runtime.key(snapshotIndex, initial) {
        SnapshotNameDialog(
            initial = initial,
            onDone = { name -> if (onRename(name)) onRenameAccepted() },
            onCancel = onCancel,
        )
    }
}

@Composable
internal fun SnapshotNameDialog(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val valid = name.isNotBlank()
    fun done() = if (valid) onDone(name.trim()) else Unit
    val c = Ae.colors
    app.croptop.core.designsystem.component.AeDialog(
        title = "Rename Snapshot",
        onDismiss = onCancel,
        onConfirm = ::done,
        width = 360.dp,
        actions = {
            app.croptop.core.designsystem.component.AeButton("Cancel", onCancel)
            app.croptop.core.designsystem.component.AeButton(
                "Rename",
                ::done,
                style = app.croptop.core.designsystem.component.AeButtonStyle.Primary,
                enabled = valid,
            )
        },
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = name,
            onValueChange = { name = it.take(120) },
            singleLine = true,
            textStyle = Ae.type.body.copy(color = c.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(Ae.density.controlHeight)
                .background(c.surface3, Ae.shapes.control)
                .border(1.dp, c.accent, Ae.shapes.control)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics { contentDescription = "Snapshot name" }
                .focusRequester(focus),
        )
    }
}
