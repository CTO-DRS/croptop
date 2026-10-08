package app.croptop.feature.editor.panels

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeSizes
import app.croptop.core.engine.api.LayerThumb as EngineLayerThumb
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.shortcutLabel
import app.croptop.core.model.BlendMode
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerNode
import app.croptop.feature.editor.dialogs.BlendOptionsDialog
import app.croptop.feature.editor.dialogs.LayerStyleDialog
import app.croptop.feature.editor.dialogs.SmartFilterDialog
import app.croptop.feature.editor.session.EditorState
import app.croptop.feature.editor.session.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private data class Row(val node: LayerNode, val depth: Int)

/**
 * LayerThumb is straight (unpremultiplied) sRGB RGBA. Its dimensions already preserve the source
 * aspect ratio inside the engine's requested pixel bound, so the panel must fit the image rather
 * than stretch it. Keep this conversion in one place for pixel and mask thumbnails.
 */
internal fun app.croptop.core.engine.api.LayerThumb.toImageBitmapOrNull(): ImageBitmap? {
    val pixels = layerThumbArgbPixels(this) ?: return null
    val bitmap = androidx.core.graphics.createBitmap(width, height)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

internal fun layerThumbArgbPixels(thumb: app.croptop.core.engine.api.LayerThumb): IntArray? {
    val width = thumb.width
    val height = thumb.height
    if (width !in 1..MAX_THUMBNAIL_DIMENSION || height !in 1..MAX_THUMBNAIL_DIMENSION) return null
    val pixelCount = width.toLong() * height
    if (pixelCount > Int.MAX_VALUE / 4 || pixelCount * 4 != thumb.rgba.size.toLong()) return null
    val pixels = IntArray(pixelCount.toInt()) { index ->
        val offset = index * 4
        android.graphics.Color.argb(
            thumb.rgba[offset + 3].toInt() and 0xff,
            thumb.rgba[offset].toInt() and 0xff,
            thumb.rgba[offset + 1].toInt() and 0xff,
            thumb.rgba[offset + 2].toInt() and 0xff,
        )
    }
    return pixels
}

/** Decode thumbnail pixels away from the UI thread and publish each burst in one Compose frame. */
private suspend fun Flow<EngineLayerThumb>.collectThumbnailsByFrame(
    onBatch: (Map<LayerId, ImageBitmap>) -> Unit,
) = coroutineScope {
    val decoded = Channel<Pair<LayerId, ImageBitmap>>(capacity = 32)
    val producer = launch(Dispatchers.Default) {
        try {
            collect { thumb ->
                thumb.toImageBitmapOrNull()?.let { decoded.send(thumb.layerId to it) }
            }
        } finally {
            decoded.close()
        }
    }
    val pending = linkedMapOf<LayerId, ImageBitmap>()
    var finished = false
    while (!finished) {
        withFrameNanos { }
        var result = decoded.receiveCatching()
        if (result.isClosed) {
            finished = true
            continue
        }
        pending[result.getOrThrow().first] = result.getOrThrow().second
        while (true) {
            result = decoded.tryReceive()
            if (result.isSuccess) {
                val item = result.getOrThrow()
                pending[item.first] = item.second
            } else {
                if (result.isClosed) finished = true
                break
            }
        }
        if (pending.isNotEmpty()) {
            onBatch(pending.toMap())
            pending.clear()
        }
    }
    producer.join()
}

private const val MAX_THUMBNAIL_DIMENSION = 512

/** Layers panel body. Header chrome comes from AePanel. */
@Composable
fun ColumnScope.LayersPanelContent(
    state: EditorState,
    vm: EditorViewModel,
    listState: LazyListState = rememberLazyListState(),
    onThumbnailDraw: ((LayerId, Long) -> Unit)? = null,
    onEditFillLayer: (LayerId) -> Unit = {},
    onEditSmartObjectContents: (LayerId) -> Unit = {},
    onReplaceSmartObjectContents: (LayerId) -> Unit = {},
) {
    val c = Ae.colors
    var collapsed by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var renaming by remember { mutableStateOf<LayerId?>(null) }
    var styleDialogTarget by remember { mutableStateOf<LayerId?>(null) }
    var blendOptionsTarget by remember { mutableStateOf<LayerId?>(null) }
    var rawDevelopTarget by remember { mutableStateOf<LayerId?>(null) }
    var smartFilterEditTarget by remember { mutableStateOf<Pair<LayerId, Int>?>(null) }
    // Drag-to-reorder state: row bounds in root coordinates, dragged id, and the current drop hint.
    val bounds = remember { mutableMapOf<LayerId, androidx.compose.ui.geometry.Rect>() }
    var dragging by remember { mutableStateOf<LayerId?>(null) }
    var hint by remember { mutableStateOf<Pair<LayerId, DropZone>?>(null) }
    fun hintAt(rootY: Float) {
        val hit = bounds.entries.firstOrNull { rootY >= it.value.top && rootY < it.value.bottom }
        hint = hit?.let { (id, r) ->
            val f = (rootY - r.top) / r.height
            val isGroup = state.layers.find(id)?.isGroup == true
            id to when {
                isGroup && f in 0.25f..0.75f -> DropZone.INTO
                f < 0.5f -> DropZone.BEFORE
                else -> DropZone.AFTER
            }
        }
    }
    val primary = state.primaryLayer
    val maskTarget by vm.maskTarget.collectAsStateWithLifecycle()
    val document by vm.document.collectAsStateWithLifecycle()
    val patterns by vm.patterns.collectAsStateWithLifecycle()
    val layerIds = remember(state.layers) { state.layers.flatten().map { it.id } }
    val maskIds = remember(state.layers) { state.layers.flatten().filter { it.mask != null }.map { it.id } }
    val layerImages = remember { mutableStateMapOf<LayerId, ImageBitmap>() }
    val maskImages = remember { mutableStateMapOf<LayerId, ImageBitmap>() }
    val thumbnailPx = with(LocalDensity.current) { 36.dp.roundToPx() }.coerceAtLeast(1)
    LaunchedEffect(document, layerIds, thumbnailPx) {
        layerImages.clear()
        val handle = document ?: return@LaunchedEffect
        if (layerIds.isEmpty()) return@LaunchedEffect
        handle.thumbnails(layerIds, thumbnailPx).collectThumbnailsByFrame { decoded ->
            layerImages.putAll(decoded)
        }
    }
    LaunchedEffect(document, maskIds, thumbnailPx) {
        maskImages.clear()
        val handle = document ?: return@LaunchedEffect
        if (maskIds.isEmpty()) return@LaunchedEffect
        handle.maskThumbnails(maskIds, thumbnailPx).collectThumbnailsByFrame { decoded ->
            maskImages.putAll(decoded)
        }
    }

    val kind = primary?.kind ?: LayerKind.PIXEL
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val blendModeControl: @Composable (Modifier) -> Unit = { modifier ->
        AeDropdown(
            options = BlendMode.availableFor(kind),
            selected = primary?.blendMode ?: BlendMode.NORMAL,
            onSelect = vm::setBlendMode,
            label = { it.displayName },
            groupOf = { it.group },
            enabled = primary != null,
            modifier = modifier,
        )
    }
    val opacityControl: @Composable (Modifier) -> Unit = { modifier ->
        AeInlineSlider(
            "Opacity",
            (primary?.opacity ?: 1f) * 100f,
            { vm.previewLayerOpacity(it) },
            modifier = modifier,
            range = 0f..100f,
            defaultValue = 100f,
            unit = "%",
            showTrack = false,
            onValueChangeFinished = vm::finishLayerOpacityEdit,
        )
    }
    // Give Blend mode and Opacity separate rows when large text would squeeze either touch target.
    if (largeText) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            blendModeControl(Modifier.fillMaxWidth())
            opacityControl(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blendModeControl(Modifier.weight(1f))
            opacityControl(Modifier.weight(1f))
        }
    }
    AeDivider()

    val rows = remember(state.layers, collapsed) {
        buildList {
            fun walk(nodes: List<LayerNode>, depth: Int) {
                nodes.forEach { n ->
                    add(Row(n, depth))
                    if (n.isGroup && n.id.value !in collapsed) walk(n.children, depth + 1)
                }
            }
            walk(state.layers.layers, 0)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
        items(rows, key = { it.node.id.value }) { row ->
            LayerRow(
                row = row,
                dropHint = hint?.takeIf { it.first == row.node.id && dragging != null }?.second,
                dragged = dragging == row.node.id,
                onBounds = { bounds[row.node.id] = it },
                onDragStart = { dragging = row.node.id },
                onDrag = ::hintAt,
                onDragEnd = {
                    val d = dragging
                    val h = hint
                    if (d != null && h != null) {
                        resolveDrop(state.layers, d, h.first, h.second)?.let { t -> vm.move(d, t.parentId, t.index) }
                    }
                    dragging = null
                    hint = null
                },
                onDragCancel = {
                    dragging = null
                    hint = null
                },
                selected = row.node.id in state.selected,
                layerThumbnail = layerImages[row.node.id],
                onThumbnailDraw = onThumbnailDraw,
                expanded = row.node.id.value !in collapsed,
                renaming = renaming == row.node.id,
                onSelect = { additive ->
                    val action = when {
                        !additive -> "Select ${row.node.name}"
                        row.node.id in state.selected -> "Remove ${row.node.name} from selection"
                        else -> "Add ${row.node.name} to selection"
                    }
                    vm.select(row.node.id, additive, action = action)
                },
                onToggleVisible = { solo ->
                    if (solo) {
                        vm.solo(row.node.id)
                    } else {
                        val visible = !row.node.visible
                        vm.setVisible(
                            row.node.id,
                            visible,
                            action = "${if (visible) "Show" else "Hide"} ${row.node.name}",
                        )
                    }
                },
                onToggleExpanded = {
                    val v = row.node.id.value
                    val wasExpanded = v !in collapsed
                    collapsed = if (v in collapsed) collapsed - v else collapsed + v
                    vm.reportUiActionCompleted("${if (wasExpanded) "Collapse" else "Expand"} ${row.node.name}")
                },
                onStartRename = {
                    renaming = row.node.id
                    vm.reportUiActionCompleted("Open Rename ${row.node.name}")
                },
                onRename = { name ->
                    vm.rename(row.node.id, name)
                    renaming = null
                },
                onCancelRename = {
                    renaming = null
                    vm.reportUiActionCompleted("Cancel Rename ${row.node.name}")
                },
                onMaskPress = { shift, ctrl, alt ->
                    val id = row.node.id
                    when {
                        ctrl -> {
                            val op = when {
                                shift && alt -> app.croptop.core.model.SelectionOp.INTERSECT
                                shift -> app.croptop.core.model.SelectionOp.ADD
                                alt -> app.croptop.core.model.SelectionOp.SUBTRACT
                                else -> app.croptop.core.model.SelectionOp.REPLACE
                            }
                            vm.maskToSelection(id, op)
                            true
                        }

                        shift -> {
                            vm.toggleMask(id)
                            true
                        }

                        alt -> {
                            vm.toggleMaskView(id)
                            true
                        }

                        else -> {
                            vm.targetMask(id)
                            true
                        }
                    }
                },
                maskThumbnail = maskImages[row.node.id],
                maskTargeted = maskTarget == row.node.id && state.primary == row.node.id,
                onTargetPixels = { vm.targetPixels(row.node.id) },
                onClipToggle = { vm.toggleClipping(row.node.id) },
                menu = {
                    val node = row.node
                    val mask = node.mask
                    val maskEntries = if (mask == null) {
                        listOf(
                            AeMenuEntry.Item("Add Layer Mask", { vm.addMask(id = node.id) }, icon = AeIcons.Mask),
                            AeMenuEntry.Item("Add Layer Mask (Hide)", { vm.addMask(hide = true, id = node.id) }),
                            AeMenuEntry.Item("Apply Layer Mask", { vm.deleteMask(node.id, apply = true) }),
                        )
                    } else {
                        listOf(
                            AeMenuEntry.Item("Toggle Solo Mask View", { vm.toggleMaskView(node.id) }, "Alt+Click"),
                            AeMenuEntry.Item(if (mask.enabled) "Disable Layer Mask" else "Enable Layer Mask", {
                                vm.toggleMask(node.id)
                            }, "Shift+Click"),
                            AeMenuEntry.Item("Invert Layer Mask", { vm.invertMask(node.id) }),
                            AeMenuEntry.Item("Load Mask as Selection", { vm.maskToSelection(node.id) }, "Ctrl+Click"),
                            AeMenuEntry.Item("Apply Layer Mask", { vm.deleteMask(node.id, apply = true) }),
                            AeMenuEntry.Item("Delete Layer Mask", { vm.deleteMask(node.id, apply = false) }),
                        )
                    }
                    val contentEntries = if (node.kind == LayerKind.FILL) {
                        listOf(AeMenuEntry.Item("Edit Fill Contents…", { onEditFillLayer(node.id) }))
                    } else if (node.kind == LayerKind.SMART_OBJECT && node.smartObject != null) {
                        val filterMask = node.smartObject?.filterMask
                        if (filterMask == null) {
                            listOf(
                                AeMenuEntry.Item("Add Filter Mask (Reveal All)", { vm.addSmartFilterMask(node.id, app.croptop.core.engine.api.MaskFill.REVEAL_ALL) }),
                                AeMenuEntry.Item("Add Filter Mask (Hide All)", { vm.addSmartFilterMask(node.id, app.croptop.core.engine.api.MaskFill.HIDE_ALL) }),
                                AeMenuEntry.Item(
                                    "Add Filter Mask from Selection",
                                    { vm.addSmartFilterMask(node.id, app.croptop.core.engine.api.MaskFill.REVEAL_SELECTION) },
                                    enabled = state.hasSelection,
                                ),
                            )
                        } else {
                            listOf(
                                AeMenuEntry.Item("Paint Filter Mask…", { vm.startFilterMaskPaint(node.id) }),
                                AeMenuEntry.Item(
                                    if (filterMask.enabled) "Disable Filter Mask" else "Enable Filter Mask",
                                    { vm.setSmartFilterMaskEnabled(node.id, !filterMask.enabled) },
                                ),
                                AeMenuEntry.Item("Invert Filter Mask", { vm.invertSmartFilterMask(node.id) }),
                                AeMenuEntry.Item("Delete Filter Mask", { vm.deleteSmartFilterMask(node.id) }),
                            )
                        }
                    } else {
                        emptyList()
                    }
                    listOf(
                        AeMenuEntry.Item("New Layer", { vm.newLayer() }, "Ctrl+Shift+N", AeIcons.Add),
                        AeMenuEntry.Item("Group Layers", { vm.groupSelected() }, "Ctrl+G", AeIcons.Group),
                        AeMenuEntry.Divider,
                        AeMenuEntry.Item("Rename…", {
                            renaming = row.node.id
                            vm.reportUiActionCompleted("Open Rename ${row.node.name}")
                        }),
                        *(
                            if (row.node.smartObject?.raw != null) {
                                arrayOf<AeMenuEntry>(
                                    AeMenuEntry.Item("Develop Settings…", {
                                        rawDevelopTarget = row.node.id
                                        vm.reportUiActionCompleted("Open Develop Settings for ${row.node.name}")
                                    }),
                                )
                            } else {
                                emptyArray<AeMenuEntry>()
                            }
                            ),
                        AeMenuEntry.Item("Blending Options…", {
                            blendOptionsTarget = row.node.id
                            vm.reportUiActionCompleted("Open Blending Options for ${row.node.name}")
                        }),
                        AeMenuEntry.Item("Layer Style…", {
                            styleDialogTarget = row.node.id
                            vm.reportUiActionCompleted("Open Layer Style for ${row.node.name}")
                        }),
                        AeMenuEntry.Item(if (row.node.visible) "Hide Layer" else "Show Layer", {
                            val visible = !row.node.visible
                            vm.setVisible(
                                row.node.id,
                                visible,
                                action = "${if (visible) "Show" else "Hide"} ${row.node.name}",
                            )
                        }),
                        AeMenuEntry.Divider,
                    ) + contentEntries +
                        (
                            if (node.kind == LayerKind.SMART_OBJECT) {
                                listOf(
                                    AeMenuEntry.Item(
                                        "Edit Contents in New Tab",
                                        { onEditSmartObjectContents(node.id) },
                                    ),
                                    AeMenuEntry.Item(
                                        "Replace Contents…",
                                        { onReplaceSmartObjectContents(node.id) },
                                    ),
                                )
                            } else {
                                emptyList()
                            }
                            ) + maskEntries + listOf(
                            AeMenuEntry.Item(
                                if (node.clipped) "Release Clipping Mask" else "Create Clipping Mask",
                                { vm.toggleClipping(node.id) },
                                "Ctrl+Alt+G",
                            ),
                            AeMenuEntry.Divider,
                            AeMenuEntry.Item("Delete Layer", { vm.deleteSelected() }, "Del", AeIcons.Delete),
                        )
                },
            )
            if (row.node.kind == LayerKind.SMART_OBJECT && row.node.smartObject?.filters.orEmpty().isNotEmpty()) {
                val filters = row.node.smartObject?.filters.orEmpty()
                val names = vm.filterSpecs.associate { it.id to it.name }
                Column(
                    Modifier.fillMaxWidth().padding(start = 40.dp, end = 8.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AeText(
                            "Smart Filters",
                            style = Ae.type.caption,
                            color = Ae.colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        AeText("${filters.size}", style = Ae.type.caption, color = Ae.colors.textSecondary)
                    }
                    filters.forEachIndexed { index, filter ->
                        val name = names[filter.specId] ?: filter.specId
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 36.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            AeText(name, style = Ae.type.caption, modifier = Modifier.weight(1f), maxLines = 1)
                            AeIconButton(
                                AeIcons.Adjustments,
                                "Edit $name smart filter ${index + 1}",
                                onClick = { smartFilterEditTarget = row.node.id to index },
                                enabled = vm.filterSpecs.any { it.id == filter.specId },
                            )
                            AeIconButton(
                                AeIcons.ChevronDown,
                                "Move $name earlier",
                                onClick = {
                                    val reordered = filters.toMutableList().apply { add(index - 1, removeAt(index)) }
                                    vm.setSmartFilters(row.node.id, reordered)
                                },
                                modifier = Modifier.rotate(180f),
                                enabled = index > 0,
                            )
                            AeIconButton(
                                AeIcons.ChevronDown,
                                "Move $name later",
                                onClick = {
                                    val reordered = filters.toMutableList().apply { add(index + 1, removeAt(index)) }
                                    vm.setSmartFilters(row.node.id, reordered)
                                },
                                enabled = index < filters.lastIndex,
                            )
                            AeIconButton(
                                AeIcons.Delete,
                                "Remove $name",
                                onClick = { vm.setSmartFilters(row.node.id, filters.filterIndexed { i, _ -> i != index }) },
                            )
                        }
                    }
                }
            }
        }
    }
    smartFilterEditTarget?.let { (layerId, filterIndex) ->
        val filter = state.layers.find(layerId)?.smartObject?.filters?.getOrNull(filterIndex)
        val spec = filter?.let { target -> vm.filterSpecs.firstOrNull { it.id == target.specId } }
        if (filter != null && spec != null) {
            SmartFilterDialog(
                spec = spec,
                initial = filter,
                onApply = { updated ->
                    val current = state.layers.find(layerId)?.smartObject?.filters.orEmpty()
                    if (current.getOrNull(filterIndex)?.specId != filter.specId) {
                        false
                    } else {
                        vm.setSmartFilters(layerId, current.toMutableList().apply { set(filterIndex, updated) })
                    }
                },
                onDismiss = { smartFilterEditTarget = null },
            )
        } else {
            smartFilterEditTarget = null
        }
    }
    rawDevelopTarget?.let { id ->
        val raw = state.layers.find(id)?.smartObject?.raw
        if (raw == null) {
            rawDevelopTarget = null
        } else {
            app.croptop.feature.editor.dialogs.RawDevelopDialog(
                layerId = id,
                layerName = state.layers.find(id)?.name ?: "RAW",
                initial = raw.settings,
                onApply = { layer, settings -> vm.setRawDevelopSettings(layer, settings) },
                onDismiss = { rawDevelopTarget = null },
            )
        }
    }
    blendOptionsTarget?.let { id ->
        state.layers.find(id)?.let { node ->
            BlendOptionsDialog(
                layerId = id,
                layerName = node.name,
                initialFill = node.fillOpacity,
                initialBlendIf = node.blendIf,
                onApply = vm::setBlendOptions,
                onPreview = { layer, blendIf -> vm.previewBlendIf(layer, blendIf) },
                onPreviewFill = { layer, fill -> vm.previewFillOpacity(layer, fill) },
                onDismiss = { blendOptionsTarget = null },
            )
        } ?: run { blendOptionsTarget = null }
    }
    styleDialogTarget?.let { id ->
        state.layers.find(id)?.let { node ->
            LayerStyleDialog(
                layerId = id,
                layerName = node.name,
                initial = node.styles,
                onPreview = vm::previewLayerStyles,
                onApply = vm::setLayerStyles,
                onCopy = vm::copyLayerStyle,
                onPaste = vm::pastedLayerStyle,
                patterns = patterns,
                patternThumbnail = vm::patternThumbnail,
                onDismiss = { styleDialogTarget = null },
            )
        }
    }
    AeDivider()
    Row(
        Modifier.fillMaxWidth().height(maxOf(40.dp, Ae.density.controlHeight)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        AeIconButton(
            AeIcons.Mask,
            "Add layer mask",
            { vm.addMask() },
            enabled = state.primaryLayer?.let { it.mask == null } == true,
        )
        var adjustMenu by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            AeIconButton(AeIcons.Adjustments, "New adjustment layer", {
                adjustMenu = true
                vm.reportUiActionCompleted("Open adjustment layer menu")
            })
            app.croptop.core.designsystem.component.AeMenu(
                expanded = adjustMenu,
                onDismiss = {
                    adjustMenu = false
                    vm.reportUiActionCompleted("Close adjustment layer menu")
                },
                entries = vm.adjustmentSpecs.map { spec ->
                    AeMenuEntry.Item(spec.name, { vm.addAdjustmentLayer(spec.id) })
                },
            )
        }
        AeIconButton(AeIcons.Group, "Group layers", { vm.groupSelected() }, shortcut = shortcutLabel("layer.group"))
        AeIconButton(AeIcons.Add, "New layer", { vm.newLayer() }, shortcut = shortcutLabel("layer.new"))
        AeIconButton(
            AeIcons.Delete,
            "Delete layer",
            { vm.deleteSelected() },
            shortcut = shortcutLabel("layer.delete"),
        )
    }
}

@Composable
private fun LayerRow(
    row: Row,
    dropHint: DropZone?,
    dragged: Boolean,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (rootY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    selected: Boolean,
    layerThumbnail: ImageBitmap?,
    onThumbnailDraw: ((LayerId, Long) -> Unit)?,
    expanded: Boolean,
    renaming: Boolean,
    onSelect: (additive: Boolean) -> Unit,
    onToggleVisible: (solo: Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onMaskPress: (shift: Boolean, ctrl: Boolean, alt: Boolean) -> Boolean = { _, _, _ -> false },
    maskThumbnail: ImageBitmap? = null,
    maskTargeted: Boolean = false,
    onTargetPixels: () -> Unit = {},
    onClipToggle: () -> Unit = {},
    menu: () -> List<AeMenuEntry>,
) {
    val c = Ae.colors
    val n = row.node
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val keyFocused by interaction.collectIsFocusedAsState()
    val dispatcher = LocalActionDispatcher.current
    var rootTop by remember { mutableFloatStateOf(0f) }
    AeContextMenuArea(entries = menu) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onGloballyPositioned {
                    val b = it.boundsInRoot()
                    rootTop = b.top
                    onBounds(b)
                }
                .alpha(if (dragged) 0.5f else 1f)
                .background(
                    if (selected) {
                        c.accentMuted
                    } else if (hovered) {
                        c.surface2
                    } else {
                        Color.Transparent
                    },
                )
                .drawWithContent {
                    drawContent()
                    val w = 2.dp.toPx()
                    when (dropHint) {
                        DropZone.BEFORE -> drawRect(c.accent, Offset(0f, 0f), Size(size.width, w))
                        DropZone.AFTER -> drawRect(c.accent, Offset(0f, size.height - w), Size(size.width, w))
                        DropZone.INTO -> drawRect(c.accent, style = Stroke(w))
                        null -> Unit
                    }
                    if (keyFocused) drawRect(c.accent, style = Stroke(w))
                }
                .hoverable(interaction)
                // Keyboard (LS-K1): Tab reaches each row; Enter selects, Shift+Enter adds, Space toggles visibility (Alt+Space solos), F2 renames.
                .focusable(interactionSource = interaction)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onSelect(e.isShiftPressed || e.isCtrlPressed)
                            true
                        }

                        Key.Spacebar -> {
                            onToggleVisible(e.isAltPressed)
                            true
                        }

                        Key.F2 -> {
                            onStartRename()
                            true
                        }

                        else -> false
                    }
                }
                .pointerInput(n.id) {
                    // Long-press to reorder so an ordinary vertical drag remains a list scroll.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            onDrag(rootTop + change.position.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() },
                    )
                }
                .semantics {
                    this.selected = selected
                    contentDescription = buildString {
                        append(n.name)
                        append(if (n.visible) ", visible" else ", hidden")
                        if (n.styles?.anyEnabled == true) append(", layer styles enabled")
                    }
                }
                .pointerInput(n.id) {
                    // Click selects; Ctrl/Shift-click toggles into the selection; double-click renames.
                    awaitPointerEventScope {
                        var lastClick = 0L
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.type != PointerEventType.Press || !ev.isPrimaryPress()) continue
                            // The eye and mask thumbnail consume their own clicks.
                            if (ev.changes.any { it.isConsumed }) continue
                            val km = ev.keyboardModifiers
                            // Alt-click on the line below a layer clips it to the next one (Photoshop).
                            if (km.isAltPressed && ev.changes.first().position.y >= size.height - CLIP_EDGE.toPx()) {
                                onClipToggle()
                                continue
                            }
                            val now = ev.changes.first().uptimeMillis
                            if (now - lastClick < 400) onStartRename() else onSelect(km.isCtrlPressed || km.isShiftPressed || km.isMetaPressed)
                            lastClick = now
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(AeSizes.selectedBar).fillMaxHeight().background(if (selected) c.accent else Color.Transparent))
            EyeToggle(n.visible, onToggleVisible)
            Spacer(Modifier.width((row.depth * 16).dp))
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (n.isGroup) {
                    Icon(
                        AeIcons.ChevronRight,
                        contentDescription = if (expanded) "Collapse group" else "Expand group",
                        tint = c.textSecondary,
                        modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f).clickable(onClick = onToggleExpanded),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            if (n.clipped) ClipArrow()
            TargetFrame(
                active = n.mask != null && !maskTargeted,
                onClick = if (n.mask != null) onTargetPixels else null,
                onModifiedClick = { additive, clip ->
                    if (clip) onClipToggle() else onSelect(additive)
                },
            ) {
                LayerThumb(
                    n,
                    layerThumbnail,
                    isBackground = row.depth == 0 && n.locks.transparency && n.locks.position,
                    onThumbnailDraw = onThumbnailDraw,
                )
            }
            n.mask?.let { mask ->
                Spacer(Modifier.width(4.dp))
                TargetFrame(active = maskTargeted) { MaskThumb(mask, maskThumbnail, onMaskPress) }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (renaming) {
                    RenameField(n.name, onRename, onCancelRename, onFocus = { dispatcher.textInputActive = it })
                } else {
                    AeText(n.name, color = if (n.visible) c.textPrimary else c.textTertiary)
                }
            }
            if (n.styles?.anyEnabled == true) {
                AeText(
                    "fx",
                    color = c.accent,
                    modifier = Modifier.padding(end = 8.dp).semantics {
                        contentDescription = "Layer styles enabled"
                    },
                )
            }
            if (n.locks.any) {
                Icon(AeIcons.Lock, contentDescription = "Locked", tint = c.textTertiary, modifier = Modifier.padding(end = 8.dp).size(14.dp))
            }
        }
    }
}

@Composable
private fun EyeToggle(visible: Boolean, onToggle: (solo: Boolean) -> Unit) {
    val c = Ae.colors
    val label = if (visible) "Hide layer" else "Show layer"
    Box(
        Modifier
            .size(40.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Press && ev.buttons.isPrimaryPressed && ev.keyboardModifiers.isAltPressed) {
                            onToggle(true)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = { onToggle(false) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (visible) AeIcons.Visible else AeIcons.Hidden,
            null,
            tint = if (visible) c.textSecondary else c.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Layer mask thumbnail (from the engine when available, white while the native thumbnail path is pending).
 * A disabled mask shows a red cross. Shift-click toggles it, Ctrl-click loads it as a selection.
 */
@Composable
private fun MaskThumb(
    mask: app.croptop.core.model.LayerMaskInfo,
    thumbnail: ImageBitmap?,
    onPress: (shift: Boolean, ctrl: Boolean, alt: Boolean) -> Boolean,
) {
    val c = Ae.colors
    val danger = c.danger
    Box(
        Modifier
            .size(40.dp)
            .border(1.dp, c.lineStrong, Ae.shapes.chip)
            .semantics {
                contentDescription = if (mask.enabled) "Layer mask" else "Layer mask, disabled"
            }
            .clickable(onClickLabel = "Target layer mask", onClick = { onPress(false, false, false) })
            .pointerInput(mask) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type != PointerEventType.Press || !ev.isPrimaryPress()) continue
                        val km = ev.keyboardModifiers
                        if (!km.isShiftPressed && !km.isCtrlPressed && !km.isMetaPressed && !km.isAltPressed) continue
                        if (onPress(km.isShiftPressed, km.isCtrlPressed || km.isMetaPressed, km.isAltPressed)) ev.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        if (thumbnail != null) {
            ThumbnailCheckerboard(Modifier.fillMaxSize().padding(1.dp))
            Image(thumbnail, null, Modifier.fillMaxSize().padding(1.dp), contentScale = ContentScale.Fit)
        } else {
            Canvas(Modifier.fillMaxSize().padding(1.dp)) {
                drawRect(Color.White.copy(alpha = mask.density.coerceIn(0.15f, 1f)))
            }
        }
        if (!mask.enabled) {
            Canvas(Modifier.fillMaxSize().padding(1.dp)) {
                val w = 2.dp.toPx()
                drawLine(danger, Offset(0f, 0f), Offset(size.width, size.height), w)
                drawLine(danger, Offset(size.width, 0f), Offset(0f, size.height), w)
            }
        }
    }
}

/** The accent frame Photoshop draws around the thumbnail that receives painting (pixels or mask). */
@Composable
private fun TargetFrame(
    active: Boolean,
    onClick: (() -> Unit)? = null,
    onModifiedClick: (additive: Boolean, clip: Boolean) -> Unit = { _, _ -> },
    content: @Composable () -> Unit,
) {
    val c = Ae.colors
    Box(
        Modifier
            .border(1.5.dp, if (active) c.textPrimary else Color.Transparent, Ae.shapes.chip)
            .padding(2.dp)
            .then(if (onClick == null) Modifier else Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp))
            .then(if (onClick == null) Modifier else Modifier.semantics { contentDescription = "Target layer pixels" })
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Target layer pixels",
                        onClick = onClick,
                    )
                },
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(onClick) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type != PointerEventType.Press || !ev.isPrimaryPress()) continue
                                val modifiers = ev.keyboardModifiers
                                val additive = modifiers.isCtrlPressed || modifiers.isShiftPressed || modifiers.isMetaPressed
                                if (additive || modifiers.isAltPressed) {
                                    val clip = modifiers.isAltPressed && ev.changes.first().position.y >= size.height - CLIP_EDGE.toPx()
                                    onModifiedClick(additive, clip)
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                },
            ),
    ) { content() }
}

/** The bent arrow Photoshop shows before a clipped layer. */
@Composable
private fun ClipArrow() {
    val tint = Ae.colors.textSecondary
    Canvas(Modifier.size(14.dp, 28.dp).semantics { contentDescription = "Clipped to the layer below" }) {
        val w = 1.25.dp.toPx()
        val x = size.width * 0.35f
        val y = size.height * 0.75f
        drawLine(tint, Offset(x, size.height * 0.3f), Offset(x, y), w)
        drawLine(tint, Offset(x, y), Offset(size.width - 2.dp.toPx(), y), w)
        val tip = Offset(size.width - 2.dp.toPx(), y)
        drawLine(tint, tip, Offset(tip.x - 3.dp.toPx(), y - 3.dp.toPx()), w)
        drawLine(tint, tip, Offset(tip.x - 3.dp.toPx(), y + 3.dp.toPx()), w)
    }
}

/** Engine thumbnail for pixel content, with a kind-specific fallback while it loads. */
@Composable
private fun LayerThumb(
    n: LayerNode,
    thumbnail: ImageBitmap?,
    isBackground: Boolean = false,
    onThumbnailDraw: ((LayerId, Long) -> Unit)? = null,
) {
    val c = Ae.colors
    val fill = if (isBackground) app.croptop.feature.editor.canvas.LocalDocumentFill.current else null
    Box(
        Modifier.size(36.dp, 28.dp).border(1.dp, c.lineStrong, Ae.shapes.chip),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            ThumbnailCheckerboard(Modifier.fillMaxSize().padding(1.dp))
            val imageModifier = Modifier.fillMaxSize().padding(1.dp)
            Image(
                thumbnail,
                "Layer thumbnail for ${n.name}",
                if (onThumbnailDraw == null) {
                    imageModifier
                } else {
                    imageModifier.drawWithContent {
                        drawContent()
                        onThumbnailDraw(n.id, System.nanoTime())
                    }
                },
                contentScale = ContentScale.Fit,
            )
        } else {
            when (n.kind) {
                LayerKind.GROUP -> Icon(AeIcons.Group, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))

                LayerKind.ADJUSTMENT -> Icon(AeIcons.Adjustments, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))

                LayerKind.TEXT -> Icon(AeIcons.Text, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))

                LayerKind.SHAPE -> Icon(AeIcons.Rectangle, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))

                else -> Canvas(Modifier.size(34.dp, 26.dp)) {
                    if (fill != null) {
                        drawRect(fill)
                        return@Canvas
                    }
                    val cell = 4.dp.toPx()
                    var y = 0f
                    var r = 0
                    while (y < size.height) {
                        var x = 0f
                        var col = r
                        while (x < size.width) {
                            drawRect(if (col % 2 == 0) Color(0xFFBDBDBD) else Color(0xFF8E8E8E), Offset(x, y), Size(cell, cell))
                            x += cell
                            col++
                        }
                        y += cell
                        r++
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = 4.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var column = row
            while (x < size.width) {
                drawRect(
                    if ((column + row) % 2 == 0) Color(0xFFBDBDBD) else Color(0xFF8E8E8E),
                    Offset(x, y),
                    Size(cell, cell),
                )
                x += cell
                column++
            }
            y += cell
            row++
        }
    }
}

@Composable
internal fun RenameField(initial: String, onCommit: (String) -> Unit, onCancel: () -> Unit, onFocus: (Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val c = Ae.colors
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = Ae.type.body.copy(color = c.textPrimary),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, Ae.shapes.chip)
            .border(1.dp, c.accent, Ae.shapes.chip)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .focusRequester(focus)
            .semantics { contentDescription = "Layer or group name" }
            .onFocusChanged { onFocus(it.isFocused) }
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.Escape -> {
                        onCancel()
                        true
                    }

                    Key.Enter, Key.NumPadEnter -> {
                        onCommit(text)
                        true
                    }

                    else -> false
                }
            },
    )
}

/** Height of the band at a row's bottom edge where Alt-click toggles clipping. */
private val CLIP_EDGE = 8.dp

/**
 * A primary press: the mouse's primary button, or any touch/stylus contact (touch events carry no button state,
 * so `buttons.isPrimaryPressed` alone ignores finger taps).
 */
private fun androidx.compose.ui.input.pointer.PointerEvent.isPrimaryPress(): Boolean =
    buttons.isPrimaryPressed || changes.any { it.type != PointerType.Mouse }
