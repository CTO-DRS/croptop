package app.croptop.feature.editor.path

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeColorWell
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.ShapeBooleanOperation
import app.croptop.core.model.PathAnchorMode
import app.croptop.core.model.PathStrokeAlignment
import app.croptop.core.model.PathStrokeCap
import app.croptop.core.model.PathStrokeJoin
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ShapeToolOptions
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.toPathStyle

private enum class ShapeColorTarget { FILL, STROKE }
private enum class PathColorTarget { FILL, STROKE }

/** Shape/Pen options bar. It edits tool defaults only; it does not create a document edit. */
@Composable
fun ShapeOptionsBar(
    options: ShapeToolOptions,
    onOptionsChange: (ShapeToolOptions) -> Unit,
    modifier: Modifier = Modifier,
    onActionCompleted: (String) -> Unit = {},
) {
    var colorTarget by remember { mutableStateOf<ShapeColorTarget?>(null) }
    val lineOnly = options.kind == VectorShapeKind.LINE
    fun closeColorPicker() {
        val target = colorTarget ?: return
        colorTarget = null
        onActionCompleted("Close shape ${if (target == ShapeColorTarget.FILL) "fill" else "stroke"} color picker")
    }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AeDropdown(
            options = VectorShapeKind.entries,
            selected = options.kind,
            onSelect = { kind ->
                onOptionsChange(
                    options.copy(
                        kind = kind,
                        fillEnabled = if (kind ==
                            VectorShapeKind.LINE
                        ) {
                            false
                        } else {
                            options.fillEnabled
                        },
                    ),
                )
                onActionCompleted("Set shape type to ${kind.label.lowercase()}")
            },
            label = { it.label },
        )
        AeCheckbox(
            checked = options.fillEnabled,
            onCheckedChange = {
                onOptionsChange(options.copy(fillEnabled = it))
                onActionCompleted(if (it) "Enable shape fill" else "Disable shape fill")
            },
            label = "Fill",
            enabled = !lineOnly,
        )
        AeColorWell(options.fillColor, "Shape fill", size = 22, onClick = {
            colorTarget = ShapeColorTarget.FILL
            onActionCompleted("Open shape fill color picker")
        })
        AeCheckbox(
            checked = options.strokeEnabled,
            onCheckedChange = {
                onOptionsChange(options.copy(strokeEnabled = it))
                onActionCompleted(if (it) "Enable shape stroke" else "Disable shape stroke")
            },
            label = "Stroke",
        )
        AeColorWell(options.strokeColor, "Shape stroke", size = 22, onClick = {
            colorTarget = ShapeColorTarget.STROKE
            onActionCompleted("Open shape stroke color picker")
        })
        AeInlineSlider(
            label = "Width",
            value = options.strokeWidth,
            onValueChange = { onOptionsChange(options.copy(strokeWidth = it).normalized()) },
            range = ShapeToolOptions.MIN_STROKE_WIDTH..512f,
            defaultValue = 1f,
            step = 0.5f,
            decimals = 1,
            unit = "px",
            trackWidth = 72.dp,
            showTrack = false,
            onValueChangeFinished = { onActionCompleted("Set shape stroke width") },
        )
        when (options.kind) {
            VectorShapeKind.ROUNDED_RECTANGLE -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CornerRadiusSlider("TL", "top-left", options.cornerRadii.topLeft, onOptionsChange, onActionCompleted) {
                    options.copy(cornerRadii = options.cornerRadii.copy(topLeft = it).normalized())
                }
                CornerRadiusSlider("TR", "top-right", options.cornerRadii.topRight, onOptionsChange, onActionCompleted) {
                    options.copy(cornerRadii = options.cornerRadii.copy(topRight = it).normalized())
                }
                CornerRadiusSlider("BR", "bottom-right", options.cornerRadii.bottomRight, onOptionsChange, onActionCompleted) {
                    options.copy(cornerRadii = options.cornerRadii.copy(bottomRight = it).normalized())
                }
                CornerRadiusSlider("BL", "bottom-left", options.cornerRadii.bottomLeft, onOptionsChange, onActionCompleted) {
                    options.copy(cornerRadii = options.cornerRadii.copy(bottomLeft = it).normalized())
                }
            }

            VectorShapeKind.POLYGON, VectorShapeKind.STAR -> {
                AeInlineSlider(
                    label = "Sides",
                    value = options.polygonSides.toFloat(),
                    onValueChange = { onOptionsChange(options.copy(polygonSides = it.toInt()).normalized()) },
                    range = ShapeToolOptions.MIN_POLYGON_SIDES.toFloat()..ShapeToolOptions.MAX_POLYGON_SIDES.toFloat(),
                    defaultValue = 5f,
                    step = 1f,
                    decimals = 0,
                    trackWidth = 52.dp,
                    showTrack = false,
                    onValueChangeFinished = { onActionCompleted("Set polygon sides") },
                )
                if (options.kind == VectorShapeKind.STAR) {
                    AeInlineSlider(
                        label = "Inset",
                        value = options.starInnerRatio,
                        onValueChange = { onOptionsChange(options.copy(starInnerRatio = it).normalized()) },
                        range = ShapeToolOptions.MIN_STAR_INNER_RATIO..ShapeToolOptions.MAX_STAR_INNER_RATIO,
                        defaultValue = 0.5f,
                        step = 0.01f,
                        decimals = 2,
                        trackWidth = 52.dp,
                        showTrack = false,
                        onValueChangeFinished = { onActionCompleted("Set star inset") },
                    )
                }
                AeInlineSlider(
                    label = "Rotate",
                    value = options.rotationDegrees,
                    onValueChange = { onOptionsChange(options.copy(rotationDegrees = it).normalized()) },
                    range = -180f..180f,
                    defaultValue = 0f,
                    step = 1f,
                    decimals = 0,
                    unit = "°",
                    trackWidth = 52.dp,
                    showTrack = false,
                    onValueChangeFinished = { onActionCompleted("Rotate shape") },
                )
            }

            else -> Unit
        }
        AeDropdown(options = PathStrokeJoin.entries, selected = options.join, onSelect = {
            onOptionsChange(options.copy(join = it))
            onActionCompleted("Set shape stroke join to ${it.name.lowercase()}")
        }, label = { it.name })
        if (options.join == PathStrokeJoin.MITER) {
            AeInlineSlider(
                label = "Miter",
                value = options.miterLimit,
                onValueChange = { onOptionsChange(options.copy(miterLimit = it).normalized()) },
                range = ShapeToolOptions.MIN_MITER_LIMIT..ShapeToolOptions.MAX_MITER_LIMIT,
                defaultValue = ShapeToolOptions.DEFAULT_MITER_LIMIT,
                step = 0.5f,
                decimals = 1,
                trackWidth = 56.dp,
                showTrack = false,
                onValueChangeFinished = { onActionCompleted("Set shape miter limit") },
            )
        }
        AeDropdown(options = PathStrokeCap.entries, selected = options.cap, onSelect = {
            onOptionsChange(options.copy(cap = it))
            onActionCompleted("Set shape stroke cap to ${it.name.lowercase()}")
        }, label = { it.name })
        AeDropdown(
            options = PathStrokeAlignment.entries,
            selected = options.alignment,
            onSelect = {
                onOptionsChange(options.copy(alignment = it))
                onActionCompleted("Set shape stroke alignment to ${it.name.lowercase()}")
            },
            label = { it.name },
            enabled = options.kind != VectorShapeKind.LINE,
        )
        AeDropdown(
            options = ShapeDashPreset.entries,
            selected = ShapeDashPreset.from(options.dashLengths),
            onSelect = { preset ->
                onOptionsChange(options.copy(dashLengths = preset.lengths))
                onActionCompleted("Set shape stroke to ${preset.label.lowercase()}")
            },
            label = { it.label },
        )
        if (options.dashLengths.isNotEmpty()) {
            AeInlineSlider(
                label = "Dash offset",
                value = options.dashOffset,
                onValueChange = { onOptionsChange(options.copy(dashOffset = it).normalized()) },
                range = -512f..512f,
                defaultValue = 0f,
                step = 1f,
                decimals = 0,
                unit = "px",
                trackWidth = 56.dp,
                showTrack = false,
                onValueChangeFinished = { onActionCompleted("Set shape dash offset") },
            )
        }
        if (options.kind == VectorShapeKind.LINE) {
            AeCheckbox(options.arrowAtStart, {
                onOptionsChange(options.copy(arrowAtStart = it))
                onActionCompleted(if (it) "Add start arrow" else "Remove start arrow")
            }, "Start arrow")
            AeCheckbox(options.arrowAtEnd, {
                onOptionsChange(options.copy(arrowAtEnd = it))
                onActionCompleted(if (it) "Add end arrow" else "Remove end arrow")
            }, "End arrow")
        }
    }
    colorTarget?.let { target ->
        val current = if (target == ShapeColorTarget.FILL) options.fillColor else options.strokeColor
        AeDialog(
            title = if (target == ShapeColorTarget.FILL) "Shape Fill" else "Shape Stroke",
            onDismiss = ::closeColorPicker,
            onConfirm = ::closeColorPicker,
            actions = {
                AeButton("Done", ::closeColorPicker, style = app.croptop.core.designsystem.component.AeButtonStyle.Primary)
            },
        ) {
            AeColorPicker(
                current,
                { color ->
                    val next = if (target == ShapeColorTarget.FILL) options.copy(fillColor = color) else options.copy(strokeColor = color)
                    onOptionsChange(next)
                },
                onEditComplete = {
                    onActionCompleted("Set shape ${if (target == ShapeColorTarget.FILL) "fill" else "stroke"} color")
                },
            )
        }
    }
}

private enum class ShapeDashPreset(val label: String, val lengths: List<Float>) {
    SOLID("Solid", emptyList()),
    DASH("Dash", listOf(8f, 4f)),
    DOT("Dot", listOf(1f, 3f)),
    DASH_DOT("Dash dot", listOf(8f, 3f, 1f, 3f)),
    ;

    companion object {
        fun from(lengths: List<Float>): ShapeDashPreset = entries.firstOrNull { it.lengths == lengths } ?: DASH
    }
}

@Composable
private fun CornerRadiusSlider(
    label: String,
    actionLabel: String,
    value: Float,
    onOptionsChange: (ShapeToolOptions) -> Unit,
    onActionCompleted: (String) -> Unit,
    toOptions: (Float) -> ShapeToolOptions,
) {
    AeInlineSlider(
        label = label,
        value = value,
        onValueChange = { onOptionsChange(toOptions(it)) },
        range = 0f..512f,
        defaultValue = 0f,
        step = 1f,
        decimals = 0,
        trackWidth = 36.dp,
        showTrack = false,
        onValueChangeFinished = { onActionCompleted("Set $actionLabel corner radius") },
    )
}

/** Paths dock for the selected engine-owned shape layer. */
@Composable
fun PathsPanelContent(
    controller: PathDraftController,
    modifier: Modifier = Modifier,
    onPathsChanged: (List<VectorPathDraft>) -> Boolean = { true },
    onPathToSelection: ((SelectionShape) -> Boolean)? = null,
    onPathBoolean: ((VectorPathId, VectorPathId, ShapeBooleanOperation) -> Boolean)? = null,
    onActionCompleted: (action: String) -> Unit = {},
    onActionFailed: (action: String, detail: String) -> Unit = { _, _ -> },
) {
    val state by controller.state.collectAsState()
    var pathColorTarget by remember { mutableStateOf<PathColorTarget?>(null) }
    var booleanPaths by remember(controller) { mutableStateOf<List<VectorPathId>>(emptyList()) }
    LaunchedEffect(state.paths) {
        booleanPaths = booleanPaths.filter { selectedId -> state.paths.any { it.id == selectedId } }
    }
    var previewBasePaths by remember(controller) { mutableStateOf<List<VectorPathDraft>?>(null) }
    val selected = state.selectedPathId
    val anchorSelection = state.selectedAnchor
    fun previewPathMutation(mutation: () -> Boolean) {
        if (previewBasePaths == null) previewBasePaths = controller.state.value.paths
        if (!mutation()) {
            previewBasePaths?.let(controller::loadPersistedPaths)
            previewBasePaths = null
        }
    }
    fun finishPathMutation(): Boolean {
        val previous = previewBasePaths ?: return false
        val updated = controller.state.value.paths
        previewBasePaths = null
        if (updated == previous) return false
        if (!onPathsChanged(updated)) {
            controller.loadPersistedPaths(previous)
            return false
        }
        return true
    }
    fun commitPathMutation(mutation: () -> Boolean) {
        val previous = controller.state.value.paths
        if (!mutation()) return
        val updated = controller.state.value.paths
        if (updated != previous && !onPathsChanged(updated)) controller.loadPersistedPaths(previous)
    }
    fun closePathColorPicker() {
        val target = pathColorTarget ?: return
        pathColorTarget = null
        onActionCompleted("Close path ${if (target == PathColorTarget.FILL) "fill" else "stroke"} color picker")
    }
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                AeText("Paths", style = Ae.type.panelHeader)
                AeText("Document paths", style = Ae.type.caption, color = Ae.colors.textTertiary)
            }
            AeButton(
                "Path to selection",
                onClick = {
                    val path = state.paths.firstOrNull { it.id == selected }
                    if (path == null) {
                        onActionFailed("Path to selection", "Select a path first")
                    } else {
                        val polygon = path.toSelectionPolygon()
                        if (polygon == null) {
                            val detail = "Path to selection needs one closed path with three or more anchors"
                            controller.showMessage(detail)
                            onActionFailed("Path to selection", detail)
                        } else {
                            onPathToSelection?.invoke(polygon)
                        }
                    }
                },
                enabled = selected != null && onPathToSelection != null,
            )
        }
        val booleanSelection = booleanPaths.mapNotNull { id -> state.paths.firstOrNull { it.id == id } }
        val canCombine = onPathBoolean != null && booleanSelection.size == 2 && booleanSelection.all { path ->
            path.subpaths.isNotEmpty() && path.subpaths.all { it.closed && it.anchors.size >= 3 }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "Unite" to ShapeBooleanOperation.UNITE,
                "Subtract" to ShapeBooleanOperation.SUBTRACT,
                "Intersect" to ShapeBooleanOperation.INTERSECT,
                "Exclude" to ShapeBooleanOperation.EXCLUDE,
            ).forEach { (label, operation) ->
                AeButton(
                    label,
                    onClick = {
                        val subject = booleanSelection.getOrNull(0)?.id
                        val clip = booleanSelection.getOrNull(1)?.id
                        if (subject == null || clip == null || !canCombine) {
                            onActionFailed("$label paths", "Select two closed paths with at least three anchors")
                        } else if (onPathBoolean.invoke(subject, clip, operation)) {
                            booleanPaths = emptyList()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canCombine,
                )
            }
        }
        AeText(
            when (booleanSelection.size) {
                0 -> "Choose two paths for a boolean operation"
                1 -> "Subject: ${booleanSelection[0].name} · choose a clip path"
                else -> "Subject: ${booleanSelection[0].name} · Clip: ${booleanSelection[1].name}"
            },
            style = Ae.type.caption,
            color = Ae.colors.textSecondary,
        )
        if (state.paths.isEmpty()) {
            AeText(
                "No paths on this shape layer. Draw with Pen or a shape tool.",
                style = Ae.type.caption,
                color = Ae.colors.textSecondary,
                maxLines = 3,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(state.paths, key = { it.id.value }) { path ->
                    val isSelected = path.id == selected
                    val anchorCount = path.subpaths.sumOf { it.anchors.size }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) Ae.colors.accentMuted else Ae.colors.surface1, Ae.shapes.control)
                            .clickable {
                                controller.selectPath(path.id)
                                onActionCompleted("Select path ${path.name}")
                            }
                            .semantics {
                                this.selected = isSelected
                                contentDescription = "${path.name}, $anchorCount anchors"
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        AeText(path.name, modifier = Modifier.weight(1f))
                        AeText("$anchorCount", style = Ae.type.caption, color = Ae.colors.textSecondary)
                        AeCheckbox(
                            checked = path.id in booleanPaths,
                            onCheckedChange = { checked ->
                                booleanPaths = when {
                                    checked && path.id !in booleanPaths && booleanPaths.size < 2 -> booleanPaths + path.id
                                    !checked -> booleanPaths.filterNot { it == path.id }
                                    else -> booleanPaths
                                }
                            },
                            label = "Boolean",
                            modifier = Modifier.semantics {
                                contentDescription = "Use ${path.name} in path boolean"
                            },
                        )
                    }
                }
            }
        }
        state.paths.firstOrNull { it.id == selected }?.let { selectedPath ->
            AeText("Appearance", style = Ae.type.caption, color = Ae.colors.textSecondary)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AeCheckbox(
                    checked = selectedPath.style.fillColor != null,
                    onCheckedChange = { enabled ->
                        commitPathMutation {
                            controller.setSelectedPathStyle(
                                selectedPath.style.copy(
                                    fillColor = if (enabled) {
                                        selectedPath.style.fillColor ?: state.shapeOptions.fillColor
                                    } else {
                                        null
                                    },
                                ),
                            )
                        }
                    },
                    label = "Fill",
                )
                AeCheckbox(
                    checked = selectedPath.style.strokeColor != null,
                    onCheckedChange = { enabled ->
                        commitPathMutation {
                            controller.setSelectedPathStyle(
                                selectedPath.style.copy(
                                    strokeColor = if (enabled) {
                                        selectedPath.style.strokeColor ?: state.shapeOptions.strokeColor
                                    } else {
                                        null
                                    },
                                ),
                            )
                        }
                    },
                    label = "Stroke",
                )
                AeColorWell(
                    selectedPath.style.fillColor ?: state.shapeOptions.fillColor,
                    "Path fill",
                    size = 22,
                    onClick = {
                        pathColorTarget = PathColorTarget.FILL
                        onActionCompleted("Open path fill color picker")
                    },
                )
                AeColorWell(
                    selectedPath.style.strokeColor ?: state.shapeOptions.strokeColor,
                    "Path stroke",
                    size = 22,
                    onClick = {
                        pathColorTarget = PathColorTarget.STROKE
                        onActionCompleted("Open path stroke color picker")
                    },
                )
                AeInlineSlider(
                    label = "Path width",
                    value = selectedPath.style.strokeWidth,
                    onValueChange = { value ->
                        previewPathMutation {
                            controller.setSelectedPathStyle(selectedPath.style.copy(strokeWidth = value).normalized())
                        }
                    },
                    range = ShapeToolOptions.MIN_STROKE_WIDTH..512f,
                    defaultValue = 1f,
                    step = 0.5f,
                    decimals = 1,
                    unit = "px",
                    trackWidth = 72.dp,
                    showTrack = false,
                    onValueChangeFinished = { finishPathMutation() },
                )
                AeDropdown(
                    options = PathStrokeJoin.entries,
                    selected = selectedPath.style.join,
                    onSelect = { join ->
                        commitPathMutation {
                            controller.setSelectedPathStyle(selectedPath.style.copy(join = join))
                        }
                    },
                    label = { "Join ${it.name.lowercase()}" },
                )
                AeDropdown(
                    options = PathStrokeCap.entries,
                    selected = selectedPath.style.cap,
                    onSelect = { cap ->
                        commitPathMutation {
                            controller.setSelectedPathStyle(selectedPath.style.copy(cap = cap))
                        }
                    },
                    label = { "Cap ${it.name.lowercase()}" },
                )
                AeDropdown(
                    options = ShapeDashPreset.entries,
                    selected = ShapeDashPreset.from(selectedPath.style.dashLengths),
                    onSelect = { preset ->
                        commitPathMutation {
                            controller.setSelectedPathStyle(selectedPath.style.copy(dashLengths = preset.lengths))
                        }
                    },
                    label = { "Path ${it.label.lowercase()}" },
                )
                AeButton(
                    "Apply tool style",
                    onClick = {
                        commitPathMutation {
                            controller.setSelectedPathStyle(state.shapeOptions.toPathStyle())
                        }
                    },
                )
            }
            pathColorTarget?.let { target ->
                val current =
                    if (target == PathColorTarget.FILL) {
                        selectedPath.style.fillColor ?: state.shapeOptions.fillColor
                    } else {
                        selectedPath.style.strokeColor ?: state.shapeOptions.strokeColor
                    }
                AeDialog(
                    title = if (target == PathColorTarget.FILL) "Path Fill" else "Path Stroke",
                    onDismiss = ::closePathColorPicker,
                    onConfirm = ::closePathColorPicker,
                    actions = {
                        AeButton("Done", ::closePathColorPicker, style = app.croptop.core.designsystem.component.AeButtonStyle.Primary)
                    },
                ) {
                    AeColorPicker(
                        current,
                        { color ->
                            previewPathMutation {
                                controller.setSelectedPathStyle(
                                    if (target == PathColorTarget.FILL) {
                                        selectedPath.style.copy(fillColor = color)
                                    } else {
                                        selectedPath.style.copy(strokeColor = color)
                                    },
                                )
                            }
                        },
                        onEditComplete = {
                            if (finishPathMutation()) {
                                onActionCompleted("Set path ${if (target == PathColorTarget.FILL) "fill" else "stroke"} color")
                            }
                        },
                    )
                }
            }
            val anchors = selectedPath.subpaths.flatMapIndexed { subpathIndex, subpath ->
                subpath.anchors.mapIndexed { anchorIndex, anchor ->
                    Triple(PathAnchorSelection(selectedPath.id, subpathIndex, anchor.id), subpathIndex, anchorIndex)
                }
            }
            if (anchors.isNotEmpty()) {
                AeText("Anchors", style = Ae.type.caption, color = Ae.colors.textSecondary)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    anchors.forEach { (selection, subpathIndex, anchorIndex) ->
                        val isSelected = anchorSelection == selection
                        AeButton(
                            text = "${subpathIndex + 1}.${anchorIndex + 1}",
                            onClick = {
                                controller.selectAnchor(selection)
                                if (controller.state.value.selectedAnchor == selection) {
                                    onActionCompleted("Select anchor ${subpathIndex + 1}.${anchorIndex + 1}")
                                } else {
                                    onActionFailed("Select anchor", controller.state.value.message ?: "The anchor is no longer available")
                                }
                            },
                            style = if (isSelected) {
                                app.croptop.core.designsystem.component.AeButtonStyle.Primary
                            } else {
                                app.croptop.core.designsystem.component.AeButtonStyle.Ghost
                            },
                            modifier = Modifier.semantics {
                                this.selected = isSelected
                                contentDescription = "Subpath ${subpathIndex + 1}, anchor ${anchorIndex + 1}"
                            },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AeDropdown(
                options = PathAnchorMode.entries,
                selected = selectedAnchorMode(controller, anchorSelection),
                onSelect = { mode -> anchorSelection?.let { selection -> commitPathMutation { controller.setAnchorMode(selection, mode) } } },
                label = { it.label },
                enabled = anchorSelection != null,
            )
            AeButton("Close path", { commitPathMutation(controller::closeActivePath) }, enabled = state.selectedPathId != null)
            AeButton("Delete anchor", { commitPathMutation(controller::deleteSelectedAnchor) }, enabled = anchorSelection != null)
            AeButton("Delete path", { commitPathMutation(controller::deleteSelectedPath) }, enabled = selected != null)
        }
        state.message?.let { AeText(it, style = Ae.type.caption, color = Ae.colors.danger, maxLines = 2) }
    }
}

private fun selectedAnchorMode(controller: PathDraftController, selection: PathAnchorSelection?) =
    selection?.let { current ->
        controller.state.value.paths.firstOrNull { it.id == current.pathId }
            ?.subpaths?.getOrNull(current.subpathIndex)
            ?.anchors?.firstOrNull { it.id == current.anchorId }
            ?.mode
    } ?: PathAnchorMode.CORNER
