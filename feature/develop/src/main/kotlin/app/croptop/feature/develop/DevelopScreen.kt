package app.croptop.feature.develop

import androidx.core.graphics.scale
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.croptop.core.data.thumbnails.ThumbnailLoader
import app.croptop.core.designsystem.component.AeAppHeader
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeCurveEditor
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.SearchCommandsButton
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.ActionScope
import app.croptop.core.input.ShortcutScope
import app.croptop.core.model.BuiltInPresets
import app.croptop.core.model.ColorRange
import app.croptop.core.model.DevelopProfile
import app.croptop.core.model.DevelopSection
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.BasicTone
import app.croptop.core.model.LocalMask
import app.croptop.core.model.LocalMaskTemplates
import app.croptop.core.model.MaskComponent
import app.croptop.core.ai.AiMaskSource
import app.croptop.core.model.MaskKind
import app.croptop.core.model.MaskOp
import app.croptop.core.model.Spot
import app.croptop.core.model.Upright
import app.croptop.core.model.UprightGuide
import app.croptop.core.model.GradeWheel
import app.croptop.core.model.WbPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Develop a photo; [siblings] fill the filmstrip (usually the Library's current filter). */
@Serializable
data class DevelopRoute(val uri: String, val siblings: List<String> = emptyList(), val copyId: Long? = null) : NavKey

@Composable
fun DevelopScreen(
    route: DevelopRoute,
    onBack: () -> Unit,
    onOpenInEditor: (String, DevelopSettings?) -> Unit,
    onOpenSibling: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the RAW as flattened pixels instead of a re-editable smart object. */
    onOpenFlattened: (String, DevelopSettings?) -> Unit = onOpenInEditor,
    /** Switches to the original (null) or one of the photo's virtual copies. */
    onOpenCopy: (Long?) -> Unit = {},
) {
    val vm = hiltViewModel<DevelopViewModel, DevelopViewModel.Factory>(key = "${route.uri}#${route.copyId}") { it.create(route.uri, route.copyId) }
    DevelopContent(vm, route, onBack, onOpenInEditor, onOpenSibling, modifier, onOpenFlattened = onOpenFlattened, onOpenCopy = onOpenCopy)
}

/** Develop UI for a given session; Hilt-free for screenshot tests. */
@Composable
fun DevelopContent(
    vm: DevelopViewModel,
    route: DevelopRoute,
    onBack: () -> Unit,
    onOpenInEditor: (String, DevelopSettings?) -> Unit,
    onOpenSibling: (String) -> Unit,
    modifier: Modifier = Modifier,
    previewBitmap: Bitmap? = null,
    onOpenFlattened: (String, DevelopSettings?) -> Unit = onOpenInEditor,
    onOpenCopy: (Long?) -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
    val sidecarExport by vm.sidecarExport.collectAsStateWithLifecycle()
    val hover by vm.hoverPreview.collectAsStateWithLifecycle()
    val before by vm.showBefore.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val copyScope = androidx.compose.runtime.rememberCoroutineScope()
    val cursor by vm.cursor.collectAsStateWithLifecycle()
    var pasteDialog by remember { mutableStateOf(false) }
    var syncDialog by remember { mutableStateOf(false) }
    val syncResult by vm.syncResult.collectAsStateWithLifecycle()
    var gridVisible by rememberSaveable { mutableStateOf(false) }
    var loupeActive by rememberSaveable { mutableStateOf(false) }
    var cropMode by rememberSaveable { mutableStateOf(false) }
    var clippingWarnings by rememberSaveable { mutableStateOf(false) }
    val splitView by vm.splitView.collectAsStateWithLifecycle()
    val splitBefore by vm.splitBefore.collectAsStateWithLifecycle()
    var splitAt by rememberSaveable { mutableStateOf(0.5f) }
    val splitBeforeBitmap by produceState<Bitmap?>(null, splitBefore) {
        value = splitBefore?.let { Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888) }
    }
    val selectedMask by vm.selectedMask.collectAsStateWithLifecycle()
    val maskTool by vm.maskTool.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val thumbnail by produceState(previewBitmap, route.uri) { if (value == null) value = ThumbnailLoader.load(context, route.uri, 2048) }
    // The engine's own render of the recipe replaces the quick colour-matrix approximation once the RAW is decoded.
    val frame by vm.frame.collectAsStateWithLifecycle()
    val previewOpening by vm.previewOpening.collectAsStateWithLifecycle()
    val previewUnavailable by vm.previewUnavailable.collectAsStateWithLifecycle()
    val frameBitmap by produceState<Bitmap?>(null, frame) {
        value = frame?.let { Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888) } ?: value
    }
    val bitmap = frameBitmap ?: thumbnail
    // The selected mask's coverage, drawn in red over the preview (O toggles it).
    val maskOverlay by vm.overlay.collectAsStateWithLifecycle()
    val overlayColor by vm.maskOverlayColor.collectAsStateWithLifecycle()
    val maskOverlayBitmap by produceState<Bitmap?>(null, maskOverlay) {
        value = maskOverlay?.let { Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888) }
    }
    val shown = hover ?: settings
    val matrix = remember(shown, frameBitmap != null) { if (frameBitmap != null) IDENTITY_MATRIX else ApproximatePreview.matrix(shown) }
    LaunchedEffect(cropMode) { vm.cropEditing.value = cropMode }
    val clippingSource by produceState<Bitmap?>(null, bitmap) {
        val source = bitmap
        value = if (source == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val (width, height) = ApproximatePreview.clippingAnalysisSize(source.width, source.height)
                if (width == source.width && height == source.height) source else source.scale(width, height)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(clippingSource, bitmap) {
        val derived = clippingSource
        val original = bitmap
        onDispose { if (derived != null && derived !== original) derived.recycle() }
    }
    val clippingOverlay by produceState<Bitmap?>(null, clippingSource, matrix, clippingWarnings, before) {
        val source = clippingSource
        if (!clippingWarnings || before || source == null) {
            value = null
        } else {
            value = withContext(Dispatchers.Default) {
                val pixels = IntArray(source.width * source.height)
                source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                val warnings = IntArray(pixels.size)
                val rowWidth = source.width
                for (row in 0 until source.height) {
                    currentCoroutineContext().ensureActive()
                    val start = row * rowWidth
                    for (column in 0 until rowWidth) {
                        val index = start + column
                        warnings[index] = ApproximatePreview.clippingWarningColor(matrix, pixels[index]) ?: 0
                    }
                }
                androidx.core.graphics.createBitmap(source.width, source.height).apply {
                    setPixels(warnings, 0, width, 0, 0, width, height)
                }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(clippingOverlay) {
        val overlay = clippingOverlay
        onDispose { overlay?.recycle() }
    }
    val name = android.net.Uri.decode(route.uri).substringAfterLast('/').substringAfterLast(':')

    ActionScope(ShortcutScope.DEVELOP) {
        on("develop.grid") { gridVisible = !gridVisible }
        on("develop.loupe") { loupeActive = !loupeActive }
        on("develop.develop") { if (settingsLoaded) onOpenInEditor(route.uri, vm.rawSettingsForOpen) }
        on("develop.crop") { cropMode = !cropMode }
        on("develop.clipping") { clippingWarnings = !clippingWarnings }
        on("develop.splitView") { vm.splitView.value = !vm.splitView.value }
        on("develop.beforeAfter") { vm.showBefore.value = !vm.showBefore.value }
        on("develop.maskOverlay") { vm.maskOverlayVisible.value = !vm.maskOverlayVisible.value }
        on("develop.masks") { if (vm.selectedMask.value == null && settings.masks.isNotEmpty()) vm.selectMask(0) }
        on("develop.whiteBalance") { vm.pickingWhiteBalance.value = !vm.pickingWhiteBalance.value }
    }
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { keys.requestFocus() }
    val c = Ae.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.app)
            .focusRequester(keys)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Z && e.isShiftPressed -> {
                        vm.redo()
                        true
                    }

                    e.key == Key.Z -> {
                        vm.undo()
                        true
                    }

                    e.key == Key.C && e.isShiftPressed -> {
                        vm.copySettings()
                        true
                    }

                    e.key == Key.V && e.isShiftPressed -> {
                        pasteDialog = true
                        true
                    }

                    else -> false
                }
            },
    ) {
        AeAppHeader(startPadding = 8.dp) {
            AeIconButton(AeIcons.Close, "Back", onBack)
            AeText("Develop", style = Ae.type.title)
            AeText(name, color = c.textSecondary, modifier = Modifier.weight(1f).padding(start = 8.dp))
            SearchCommandsButton()
            AeSegmented(listOf(false, true), before, { vm.showBefore.value = it }, { if (it) "Before" else "After" })
            AeButton("Split", { vm.splitView.value = !splitView }, style = if (splitView) AeButtonStyle.Primary else AeButtonStyle.Ghost)
            AeButton("Copy", vm::copySettings, style = AeButtonStyle.Ghost)
            AeButton("Paste…", { pasteDialog = true }, style = AeButtonStyle.Ghost, enabled = DevelopClipboard.settings != null)
            AeButton("Sync…", { syncDialog = true }, style = AeButtonStyle.Ghost, enabled = route.siblings.size > 1 && route.copyId == null)
            AeButton("Reset", vm::reset, enabled = !settings.isDefault)
            if (onOpenFlattened !== onOpenInEditor) {
                AeButton(
                    "Open Flattened",
                    { onOpenFlattened(route.uri, vm.rawSettingsForOpen) },
                    style = AeButtonStyle.Ghost,
                    enabled = settingsLoaded,
                )
            }
            AeButton(
                "Open in Editor",
                { onOpenInEditor(route.uri, vm.rawSettingsForOpen) },
                style = AeButtonStyle.Primary,
                enabled = settingsLoaded,
            )
        }
        AeDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(220.dp).fillMaxHeight().background(c.surface1).verticalScroll(rememberScrollState())) {
                AePanel("Presets") {
                    BuiltInPresets.all.forEach { p ->
                        ListRow(p.name, selected = false, onHover = { vm.hoverPreset(if (it) p else null) }) { vm.applyPreset(p) }
                    }
                }
                AePanel("Virtual copies") {
                    ListRow("Original", selected = route.copyId == null) { if (route.copyId != null) onOpenCopy(null) }
                    copies.forEach { copy ->
                        ListRow(copy.name, selected = route.copyId == copy.id) { if (route.copyId != copy.id) onOpenCopy(copy.id) }
                    }
                    AeButton("New virtual copy", {
                        copyScope.launch { onOpenCopy(vm.createCopy()) }
                    }, style = AeButtonStyle.Ghost, enabled = settingsLoaded)
                    route.copyId?.let { id ->
                        AeButton("Delete this copy", {
                            vm.deleteCopy(id)
                            onOpenCopy(null)
                        }, style = AeButtonStyle.Ghost)
                    }
                }
                AePanel("Snapshots") {
                    AeButton("Create snapshot", { vm.createSnapshot() }, style = AeButtonStyle.Ghost, enabled = settingsLoaded)
                    snapshots.forEach { snap ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ListRow(snap.name, selected = snap.settings == settings) { vm.applySnapshot(snap) } }
                            AeButton("Delete", { vm.deleteSnapshot(snap.id) }, style = AeButtonStyle.Ghost)
                        }
                    }
                }
                AePanel("History") {
                    history.forEachIndexed { i, step ->
                        ListRow(step.label, selected = i == cursor, dim = i > cursor) { vm.jump(i) }
                    }
                }
            }
            AeDivider(Modifier.fillMaxHeight(), vertical = true)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().background(c.canvas).padding(16.dp).graphicsLayer {
                        val zoom = if (loupeActive) 2f else 1f
                        scaleX = zoom
                        scaleY = zoom
                        clip = true
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    if (bmp == null) {
                        AeText(if (previewOpening) "Opening RAW…" else "Loading preview…", color = c.textTertiary)
                    } else {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = name,
                            contentScale = ContentScale.Fit,
                            colorFilter = if (before || frameBitmap != null) null else ColorFilter.colorMatrix(ColorMatrix(matrix)),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    splitBeforeBitmap?.takeIf { splitView && !before && !cropMode }?.let { original ->
                        Image(
                            original.asImageBitmap(),
                            contentDescription = "Before",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().drawWithContent {
                                clipRect(right = size.width * splitAt) { this@drawWithContent.drawContent() }
                            },
                        )
                        var splitBox by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                        Box(
                            Modifier.fillMaxSize().onSizeChanged { splitBox = it }.pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    if (splitBox.width > 0) splitAt = (change.position.x / splitBox.width).coerceIn(0.02f, 0.98f)
                                }
                            },
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val x = size.width * splitAt
                                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                                drawCircle(Color.White, 10.dp.toPx(), Offset(x, size.height / 2f))
                            }
                            Label("Before", Modifier.align(Alignment.TopStart))
                            Label("After", Modifier.align(Alignment.TopEnd))
                        }
                    }
                    clippingOverlay?.let { warning ->
                        Image(
                            warning.asImageBitmap(),
                            contentDescription = "Clipping warnings",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    maskOverlayBitmap?.takeIf { !before }?.let { overlay ->
                        Image(
                            overlay.asImageBitmap(),
                            contentDescription = "Selected mask coverage",
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(Color(overlayColor), androidx.compose.ui.graphics.BlendMode.SrcIn),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    val picking by vm.pickingWhiteBalance.collectAsStateWithLifecycle()
                    if (picking && frame != null && !before) {
                        val pf = frame!!
                        var pickBox by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                        var pickMissed by remember { mutableStateOf(false) }
                        Box(
                            Modifier.fillMaxSize().onSizeChanged { pickBox = it }.pointerInput(pf.width, pf.height) {
                                detectTapGestures { tap ->
                                    val fitted = FittedFrame.of(pickBox, pf.width, pf.height) ?: return@detectTapGestures
                                    if (!fitted.contains(tap)) return@detectTapGestures
                                    val n = fitted.toNorm(tap)
                                    pickMissed = !vm.pickWhiteBalance((n.x * pf.width).toInt(), (n.y * pf.height).toInt())
                                }
                            },
                        )
                        Label(if (pickMissed) "Pick a neutral gray or white area" else "Tap something that should be neutral", Modifier.align(Alignment.TopCenter))
                    }
                    val maskIndex = selectedMask
                    val liveFrame = frame
                    val guideDrawing by vm.guideEditing.collectAsStateWithLifecycle()
                    if (guideDrawing && liveFrame != null && settings.upright == Upright.GUIDED) {
                        GuideLayer(settings.uprightGuides, liveFrame.width, liveFrame.height, vm, Modifier.fillMaxSize())
                    }
                    val spotTool by vm.spotTool.collectAsStateWithLifecycle()
                    val selectedSpot by vm.selectedSpot.collectAsStateWithLifecycle()
                    val spotsActive = !before && !cropMode && liveFrame != null && SpotGeometry.placeable(settings.crop) && settings.upright == Upright.OFF &&
                        (spotTool || (selectedSpot != null && settings.spots.isNotEmpty()))
                    if (spotsActive && liveFrame != null) {
                        SpotLayer(settings.spots, selectedSpot, settings.crop, liveFrame.width, liveFrame.height, spotTool, vm, Modifier.fillMaxSize())
                    }
                    if (!before && !cropMode && !spotsActive && maskIndex != null && liveFrame != null && maskIndex in settings.masks.indices) {
                        MaskHandleLayer(settings.masks[maskIndex], maskIndex, liveFrame, maskTool, vm, Modifier.fillMaxSize())
                    }
                    if (before) Label("Before", Modifier.align(Alignment.TopStart))
                    if (hover != null) Label("Preset preview", Modifier.align(Alignment.TopEnd))
                    if (gridVisible && bmp != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            val color = Color.White.copy(alpha = 0.78f)
                            for (n in 1..2) {
                                val x = size.width * n / 3f
                                val y = size.height * n / 3f
                                drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                        }
                    }
                    if (cropMode && bmp != null) {
                        CropOverlay(settings.crop, bmp.width, bmp.height, vm)
                        Label("Drag the corners, edges or inside to crop", Modifier.align(Alignment.BottomCenter))
                    }
                }
                if (route.siblings.size > 1) {
                    AeDivider()
                    Filmstrip(route.siblings, route.uri, onOpenSibling)
                }
            }
            AeDivider(Modifier.fillMaxHeight(), vertical = true)
            Column(Modifier.width(300.dp).fillMaxHeight().background(c.surface1).verticalScroll(rememberScrollState())) {
                AePanel("XMP Sidecar") {
                    AeButton(
                        if (sidecarExport == SidecarExportState.Saving) "Saving XMP…" else "Save XMP Sidecar",
                        vm::saveXmpSidecar,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        style = AeButtonStyle.Secondary,
                        enabled = settingsLoaded && sidecarExport != SidecarExportState.Saving,
                    )
                    when (val status = sidecarExport) {
                        SidecarExportState.Idle -> Unit

                        SidecarExportState.Saving -> AeText(
                            "Writing XMP sidecar…",
                            color = c.textSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )

                        is SidecarExportState.Saved -> AeText(
                            "XMP sidecar saved (${status.bytes} bytes).",
                            color = c.textSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )

                        is SidecarExportState.Unsupported -> AeText(
                            status.message,
                            color = c.textSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )

                        is SidecarExportState.Failed -> AeText(
                            "Could not save XMP sidecar: ${status.message}",
                            color = c.danger,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Histogram(bitmap, matrix, clippingWarnings)
                DevelopPanels(settings, vm, cropMode, { cropMode = it })
                AeText(
                    "Preview is approximate for highlights, shadows, clarity, curves and color mixing until the RAW engine lands.",
                    style = Ae.type.caption,
                    color = c.textTertiary,
                    maxLines = 3,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
    if (syncDialog) {
        val others = route.siblings.count { it != route.uri }
        SectionDialog(
            title = "Sync Settings",
            body = "Apply these settings to the other $others photos in the filmstrip.",
            confirm = "Sync",
            initial = DevelopSection.entries.toSet() - DevelopSection.CROP - DevelopSection.SPOTS,
            onConfirm = { secs ->
                vm.syncTo(route.siblings, secs)
                syncDialog = false
            },
            onCancel = { syncDialog = false },
        )
    }
    syncResult?.let { count ->
        AeDialog(
            title = "Sync Settings",
            body = if (count == 0) "The other photos already had these settings." else "Updated $count ${if (count == 1) "photo" else "photos"}.",
            onDismiss = { vm.syncResult.value = null },
            onConfirm = { vm.syncResult.value = null },
            actions = { AeButton("OK", { vm.syncResult.value = null }, style = AeButtonStyle.Primary) },
        ) {}
    }
    if (pasteDialog) {
        PasteDialog(onPaste = { secs ->
            vm.paste(secs)
            pasteDialog = false
        }, onCancel = { pasteDialog = false })
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    AeText(
        text,
        style = Ae.type.label,
        color = Ae.colors.textPrimary,
        modifier = modifier.background(Ae.colors.surface2.copy(alpha = 0.85f), Ae.shapes.chip).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ListRow(text: String, selected: Boolean, dim: Boolean = false, onHover: (Boolean) -> Unit = {}, onClick: () -> Unit) {
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover(hovered) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Ae.density.rowHeight)
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
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AeText(text, color = if (dim) c.textTertiary else c.textPrimary)
    }
}

@Composable
private fun Filmstrip(uris: List<String>, current: String, onOpen: (String) -> Unit) {
    val c = Ae.colors
    val context = LocalContext.current
    LazyRow(Modifier.fillMaxWidth().height(84.dp).background(c.surface1).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(uris, key = { it }) { u ->
            val thumb by produceState<Bitmap?>(ThumbnailLoader.cached(u, 160), u) { if (value == null) value = ThumbnailLoader.load(context, u, 160) }
            Box(
                Modifier
                    .size(96.dp, 68.dp)
                    .background(c.canvas, Ae.shapes.chip)
                    .border(if (u == current) 2.dp else 1.dp, if (u == current) c.accent else c.lineHairline, Ae.shapes.chip)
                    .clickable { if (u != current) onOpen(u) }
                    .semantics { contentDescription = "Photo ${uris.indexOf(u) + 1}" },
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(AeIcons.Image, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Luminance histogram of what's on screen (computed from the preview + the same matrix). */
@Composable
private fun Histogram(bitmap: Bitmap?, matrix: FloatArray, clippingWarnings: Boolean) {
    val c = Ae.colors
    val bins by produceState<FloatArray?>(null, bitmap, matrix) {
        val bmp = bitmap ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val out = FloatArray(256)
            val step = maxOf(1, (bmp.width * bmp.height) / 60_000)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            var i = 0
            while (i < px.size) {
                val p = ApproximatePreview.apply(matrix, px[i])
                val l = (0.2126f * (p shr 16 and 0xFF) + 0.7152f * (p shr 8 and 0xFF) + 0.0722f * (p and 0xFF)).toInt().coerceIn(0, 255)
                out[l] += 1f
                i += step
            }
            out
        }
    }
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(12.dp).background(c.surface2).semantics { contentDescription = "Histogram" }) {
        val b = bins ?: return@Canvas
        val max = b.max().takeIf { it > 0f } ?: return@Canvas
        val path = Path().apply {
            moveTo(0f, size.height)
            b.forEachIndexed { i, v -> lineTo(i / 255f * size.width, size.height - (v / max) * size.height) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, c.textSecondary)
        drawLine(c.lineStrong, Offset(0f, size.height), Offset(size.width, size.height))
        if (clippingWarnings) {
            if (b.first() >
                0f
            ) {
                drawRect(
                    Color(0x994A9CFF),
                    androidx.compose.ui.geometry.Offset.Zero,
                    androidx.compose.ui.geometry.Size(size.width * 0.06f, size.height),
                )
            }
            if (b.last() >
                0f
            ) {
                drawRect(Color(0x99FF3B30), Offset(size.width * 0.94f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.06f, size.height))
            }
        }
    }
}

@Composable
private fun DevelopPanels(s: DevelopSettings, vm: DevelopViewModel, cropMode: Boolean, onCropModeChange: (Boolean) -> Unit) {
    AeButton(
        if (cropMode) "Done Cropping" else "Crop",
        { onCropModeChange(!cropMode) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = if (cropMode) AeButtonStyle.Primary else AeButtonStyle.Secondary,
    )
    if (cropMode) {
        Section("Crop") {
            Dev("Left", s.crop.left, 0f..(s.crop.right - 0.01f).coerceAtLeast(0f), 0f, vm, "Crop Left") { v -> copy(crop = crop.copy(left = v)) }
            Dev("Top", s.crop.top, 0f..(s.crop.bottom - 0.01f).coerceAtLeast(0f), 0f, vm, "Crop Top") { v -> copy(crop = crop.copy(top = v)) }
            Dev("Right", s.crop.right, (s.crop.left + 0.01f).coerceAtMost(1f)..1f, 1f, vm, "Crop Right") { v -> copy(crop = crop.copy(right = v)) }
            Dev("Bottom", s.crop.bottom, (s.crop.top + 0.01f).coerceAtMost(1f)..1f, 1f, vm, "Crop Bottom") { v -> copy(crop = crop.copy(bottom = v)) }
            Dev("Straighten", s.crop.angle, -45f..45f, 0f, vm, "Straighten", step = 0.1f, decimals = 1) { v -> copy(crop = crop.copy(angle = v)) }
        }
    }
    var mixerRange by rememberSaveable { mutableStateOf(ColorRange.RED) }
    Section("Basic") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AeText("Profile", color = Ae.colors.textSecondary, modifier = Modifier.weight(1f))
            AeDropdown(DevelopProfile.entries, s.profile, { p -> vm.set("Profile") { it.copy(profile = p) } }, { it.label })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AeText("White balance", color = Ae.colors.textSecondary, modifier = Modifier.weight(1f))
            AeDropdown(WbPreset.entries, s.whiteBalance.preset, { p ->
                vm.set("White Balance") { it.copy(whiteBalance = it.whiteBalance.copy(preset = p)) }
            }, { it.label })
        }
        val picking by vm.pickingWhiteBalance.collectAsStateWithLifecycle()
        AeButton(if (picking) "Cancel picker" else "Pick neutral (Shift+W)", {
            vm.pickingWhiteBalance.value = !picking
        }, style = if (picking) AeButtonStyle.Primary else AeButtonStyle.Secondary)
        Dev("Temp", s.whiteBalance.temperature, 2000f..12000f, 5500f, vm, "Temperature", step = 50f, track = TempTrack) { v ->
            copy(whiteBalance = whiteBalance.copy(preset = WbPreset.CUSTOM, temperature = v))
        }
        Dev("Tint", s.whiteBalance.tint, -150f..150f, 0f, vm, "Tint", track = TintTrack) { v ->
            copy(whiteBalance = whiteBalance.copy(preset = WbPreset.CUSTOM, tint = v))
        }
        Dev("Exposure", s.basic.exposure, -5f..5f, 0f, vm, "Exposure", step = 0.05f, decimals = 2) { v -> copy(basic = basic.copy(exposure = v)) }
        Dev("Contrast", s.basic.contrast, -100f..100f, 0f, vm, "Contrast") { v -> copy(basic = basic.copy(contrast = v)) }
        Dev("Highlights", s.basic.highlights, -100f..100f, 0f, vm, "Highlights") { v -> copy(basic = basic.copy(highlights = v)) }
        Dev("Shadows", s.basic.shadows, -100f..100f, 0f, vm, "Shadows") { v -> copy(basic = basic.copy(shadows = v)) }
        Dev("Whites", s.basic.whites, -100f..100f, 0f, vm, "Whites") { v -> copy(basic = basic.copy(whites = v)) }
        Dev("Blacks", s.basic.blacks, -100f..100f, 0f, vm, "Blacks") { v -> copy(basic = basic.copy(blacks = v)) }
        Dev("Texture", s.basic.texture, -100f..100f, 0f, vm, "Texture") { v -> copy(basic = basic.copy(texture = v)) }
        Dev("Clarity", s.basic.clarity, -100f..100f, 0f, vm, "Clarity") { v -> copy(basic = basic.copy(clarity = v)) }
        Dev("Dehaze", s.basic.dehaze, -100f..100f, 0f, vm, "Dehaze") { v -> copy(basic = basic.copy(dehaze = v)) }
        Dev("Vibrance", s.basic.vibrance, -100f..100f, 0f, vm, "Vibrance") { v -> copy(basic = basic.copy(vibrance = v)) }
        Dev("Saturation", s.basic.saturation, -100f..100f, 0f, vm, "Saturation") { v -> copy(basic = basic.copy(saturation = v)) }
    }
    Section("Local masks", initiallyOpen = false) {
        MasksSection(s, vm)
    }
    Section("Tone Curve", initiallyOpen = false) {
        AeCurveEditor(s.toneCurve, { curve -> vm.set("Tone Curve") { it.copy(toneCurve = curve) } })
    }
    Section("Color Mixer", initiallyOpen = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ColorRange.entries.forEach { r ->
                Box(
                    Modifier
                        .size(22.dp)
                        .background(Color(r.argb), Ae.shapes.chip)
                        .border(
                            if (r ==
                                mixerRange
                            ) {
                                2.dp
                            } else {
                                1.dp
                            },
                            if (r == mixerRange) Ae.colors.textPrimary else Ae.colors.lineStrong,
                            Ae.shapes.chip,
                        )
                        .clickable { mixerRange = r }
                        .semantics { contentDescription = r.label },
                )
            }
        }
        val h = s.mixer.of(mixerRange)
        Dev("Hue", h.hue, -100f..100f, 0f, vm, "${mixerRange.label} Hue") { v -> copy(mixer = mixer.with(mixerRange, h.copy(hue = v))) }
        Dev("Saturation", h.saturation, -100f..100f, 0f, vm, "${mixerRange.label} Saturation") { v ->
            copy(mixer = mixer.with(mixerRange, h.copy(saturation = v)))
        }
        Dev("Luminance", h.luminance, -100f..100f, 0f, vm, "${mixerRange.label} Luminance") { v ->
            copy(mixer = mixer.with(mixerRange, h.copy(luminance = v)))
        }
    }
    Section("Color Grading", initiallyOpen = false) {
        GradeRows("Shadows", s.grading.shadows, vm) { w -> copy(grading = grading.copy(shadows = w)) }
        GradeRows("Midtones", s.grading.midtones, vm) { w -> copy(grading = grading.copy(midtones = w)) }
        GradeRows("Highlights", s.grading.highlights, vm) { w -> copy(grading = grading.copy(highlights = w)) }
        Dev("Blending", s.grading.blending, 0f..100f, 50f, vm, "Grading Blending") { v -> copy(grading = grading.copy(blending = v)) }
        Dev("Balance", s.grading.balance, -100f..100f, 0f, vm, "Grading Balance") { v -> copy(grading = grading.copy(balance = v)) }
    }
    Section("Spot removal", initiallyOpen = false) {
        SpotSection(s, vm)
    }
    Section("Calibration", initiallyOpen = false) {
        Dev("Shadow tint", s.calibration.shadowTint, -100f..100f, 0f, vm, "Calibration shadow tint") { v -> copy(calibration = calibration.copy(shadowTint = v)) }
        Dev("Red hue", s.calibration.redHue, -100f..100f, 0f, vm, "Calibration red hue") { v -> copy(calibration = calibration.copy(redHue = v)) }
        Dev("Red saturation", s.calibration.redSaturation, -100f..100f, 0f, vm, "Calibration red saturation") { v -> copy(calibration = calibration.copy(redSaturation = v)) }
        Dev("Green hue", s.calibration.greenHue, -100f..100f, 0f, vm, "Calibration green hue") { v -> copy(calibration = calibration.copy(greenHue = v)) }
        Dev("Green saturation", s.calibration.greenSaturation, -100f..100f, 0f, vm, "Calibration green saturation") { v -> copy(calibration = calibration.copy(greenSaturation = v)) }
        Dev("Blue hue", s.calibration.blueHue, -100f..100f, 0f, vm, "Calibration blue hue") { v -> copy(calibration = calibration.copy(blueHue = v)) }
        Dev("Blue saturation", s.calibration.blueSaturation, -100f..100f, 0f, vm, "Calibration blue saturation") { v -> copy(calibration = calibration.copy(blueSaturation = v)) }
    }
    Section("Detail", initiallyOpen = false) {
        Dev("Sharpening", s.detail.sharpenAmount, 0f..150f, 40f, vm, "Sharpening") { v -> copy(detail = detail.copy(sharpenAmount = v)) }
        Dev("Radius", s.detail.sharpenRadius, 0.5f..3f, 1f, vm, "Sharpen Radius", step = 0.1f, decimals = 1) { v ->
            copy(detail = detail.copy(sharpenRadius = v))
        }
        Dev("Masking", s.detail.sharpenMasking, 0f..100f, 0f, vm, "Sharpen Masking") { v -> copy(detail = detail.copy(sharpenMasking = v)) }
        Dev("Noise reduction", s.detail.luminanceNoise, 0f..100f, 0f, vm, "Noise Reduction") { v -> copy(detail = detail.copy(luminanceNoise = v)) }
        Dev("Color noise", s.detail.colorNoise, 0f..100f, 25f, vm, "Color Noise") { v -> copy(detail = detail.copy(colorNoise = v)) }
    }
    Section("Optics", initiallyOpen = false) {
        AeCheckbox(s.optics.lensProfile, { on ->
            vm.set("Lens Profile") { it.copy(optics = it.optics.copy(lensProfile = on)) }
        }, "Lens profile corrections")
        AeCheckbox(s.optics.removeChromaticAberration, { on ->
            vm.set("Chromatic Aberration") { it.copy(optics = it.optics.copy(removeChromaticAberration = on)) }
        }, "Remove chromatic aberration")
        Dev("Vignetting", s.optics.vignetting, -100f..100f, 0f, vm, "Lens Vignetting") { v -> copy(optics = optics.copy(vignetting = v)) }
        Dev("Defringe purple", s.optics.defringePurple, 0f..20f, 0f, vm, "Defringe Purple") { v -> copy(optics = optics.copy(defringePurple = v)) }
        Dev("Defringe green", s.optics.defringeGreen, 0f..20f, 0f, vm, "Defringe Green") { v -> copy(optics = optics.copy(defringeGreen = v)) }
    }
    Section("Geometry", initiallyOpen = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AeText("Upright", color = Ae.colors.textSecondary, modifier = Modifier.weight(1f))
            AeDropdown(Upright.entries, s.upright, { u -> vm.set("Upright: ${u.label}") { it.copy(upright = u) } }, { it.label })
        }
        if (s.upright == Upright.GUIDED) {
            val drawing by vm.guideEditing.collectAsStateWithLifecycle()
            AeButton(
                if (drawing) "Done drawing" else "Draw guides on the photo",
                { vm.guideEditing.value = !drawing },
                style = if (drawing) AeButtonStyle.Primary else AeButtonStyle.Secondary,
                enabled = s.uprightGuides.size < UprightGuide.MAX || drawing,
            )
            AeText("Draw a line along something that should be straight, up to ${UprightGuide.MAX}.", color = Ae.colors.textSecondary)
            s.uprightGuides.forEachIndexed { i, _ ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AeText("Guide ${i + 1}", modifier = Modifier.weight(1f))
                    AeButton("Remove", { vm.deleteGuide(i) }, style = AeButtonStyle.Ghost)
                }
            }
        }
        if (s.upright == Upright.MANUAL) {
            val m = s.uprightManual
            Dev("Vertical", m.vertical, -100f..100f, 0f, vm, "Upright vertical") { v -> copy(uprightManual = uprightManual.copy(vertical = v)) }
            Dev("Horizontal", m.horizontal, -100f..100f, 0f, vm, "Upright horizontal") { v -> copy(uprightManual = uprightManual.copy(horizontal = v)) }
            Dev("Rotate", m.rotate, -45f..45f, 0f, vm, "Upright rotate", step = 0.1f, decimals = 1) { v -> copy(uprightManual = uprightManual.copy(rotate = v)) }
            Dev("Aspect", m.aspect, -100f..100f, 0f, vm, "Upright aspect") { v -> copy(uprightManual = uprightManual.copy(aspect = v)) }
            Dev("Scale", m.scale, 50f..200f, 100f, vm, "Upright scale") { v -> copy(uprightManual = uprightManual.copy(scale = v)) }
            Dev("X offset", m.xOffset, -100f..100f, 0f, vm, "Upright x offset") { v -> copy(uprightManual = uprightManual.copy(xOffset = v)) }
            Dev("Y offset", m.yOffset, -100f..100f, 0f, vm, "Upright y offset") { v -> copy(uprightManual = uprightManual.copy(yOffset = v)) }
        }
        Dev("Distortion", s.optics.distortion, -100f..100f, 0f, vm, "Distortion") { v -> copy(optics = optics.copy(distortion = v)) }
    }
    Section("Effects", initiallyOpen = false) {
        Dev("Vignette", s.effects.vignetteAmount, -100f..100f, 0f, vm, "Vignette") { v -> copy(effects = effects.copy(vignetteAmount = v)) }
        Dev("Grain", s.effects.grainAmount, 0f..100f, 0f, vm, "Grain") { v -> copy(effects = effects.copy(grainAmount = v)) }
        Dev("Grain size", s.effects.grainSize, 0f..100f, 25f, vm, "Grain Size") { v -> copy(effects = effects.copy(grainSize = v)) }
    }
}

@Composable
private fun GradeRows(name: String, w: GradeWheel, vm: DevelopViewModel, apply: DevelopSettings.(GradeWheel) -> DevelopSettings) {
    AeText(name.uppercase(), style = Ae.type.micro, color = Ae.colors.textTertiary, modifier = Modifier.padding(top = 4.dp))
    Dev("Hue", w.hue, 0f..360f, 0f, vm, "$name Hue") { v -> apply(w.copy(hue = v)) }
    Dev("Saturation", w.saturation, 0f..100f, 0f, vm, "$name Saturation") { v -> apply(w.copy(saturation = v)) }
    Dev("Luminance", w.luminance, -100f..100f, 0f, vm, "$name Luminance") { v -> apply(w.copy(luminance = v)) }
}

/** A develop slider: live preview while dragging, one history step on release. */
@Composable
private fun Dev(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    vm: DevelopViewModel,
    historyLabel: String,
    step: Float = 1f,
    decimals: Int = 0,
    track: Brush? = null,
    apply: DevelopSettings.(Float) -> DevelopSettings,
) {
    AeSlider(
        label,
        value,
        { v -> vm.preview { it.apply(v) } },
        range = range,
        defaultValue = default,
        step = step,
        decimals = decimals,
        onValueChangeFinished = { vm.commit(historyLabel) },
        trackBrush = track,
    )
}

@Composable
private fun SpotSection(s: DevelopSettings, vm: DevelopViewModel) {
    val placing by vm.spotTool.collectAsStateWithLifecycle()
    val selected by vm.selectedSpot.collectAsStateWithLifecycle()
    if (!SpotGeometry.placeable(s.crop) || s.upright != Upright.OFF) {
        AeText("Reset the straighten angle and Upright to place spots.", color = Ae.colors.textSecondary)
        return
    }
    AeButton(
        if (placing) "Done placing" else "Place spots (tap the photo)",
        { vm.spotTool.value = !placing },
        style = if (placing) AeButtonStyle.Primary else AeButtonStyle.Secondary,
        enabled = s.spots.size < Spot.MAX_SPOTS || placing,
    )
    s.spots.forEachIndexed { i, _ ->
        ListRow("Spot ${i + 1}", selected = selected == i) { vm.selectSpot(i) }
    }
    val index = selected?.takeIf { it in s.spots.indices } ?: return
    val spot = s.spots[index]
    AeSlider(
        "Size",
        spot.radius * 1000f,
        { v -> vm.previewSpot(index) { it.copy(radius = (v / 1000f).coerceIn(Spot.MIN_RADIUS, Spot.MAX_RADIUS)) } },
        range = 1f..250f,
        defaultValue = 20f,
        onValueChangeFinished = { vm.commit("Spot size") },
    )
    AeSlider(
        "Feather",
        spot.feather * 100f,
        { v -> vm.previewSpot(index) { it.copy(feather = v / 100f) } },
        range = 0f..100f,
        defaultValue = 50f,
        onValueChangeFinished = { vm.commit("Spot feather") },
    )
    AeSlider(
        "Opacity",
        spot.opacity * 100f,
        { v -> vm.previewSpot(index) { it.copy(opacity = v / 100f) } },
        range = 0f..100f,
        defaultValue = 100f,
        onValueChangeFinished = { vm.commit("Spot opacity") },
    )
    AeCheckbox(spot.heal, { on -> vm.setSpot(index, if (on) "Heal spot" else "Clone spot") { it.copy(heal = on) } }, "Match surroundings (heal)")
    AeButton("Delete spot", { vm.deleteSpot(index) }, style = AeButtonStyle.Ghost)
}

/** The range pickers of one mask component: radial feather/rotation/invert, luminance range, colour tolerance. */
@Composable
private fun MaskComponentControls(maskIndex: Int, component: Int, c: MaskComponent, vm: DevelopViewModel) {
    @Composable
    fun param(label: String, at: Int, range: ClosedFloatingPointRange<Float>, scale: Float, default: Float, history: String) = AeSlider(
        "$label",
        c.params[at] * scale,
        { v -> vm.previewMask(maskIndex) { m -> m.withParam(component, at, v / scale) } },
        range = range,
        defaultValue = default,
        onValueChangeFinished = { vm.commit(history) },
    )
    when (c.kind) {
        MaskKind.RADIAL -> {
            param("Feather", 5, 0f..100f, 100f, 50f, "Mask feather")
            param("Rotation", 4, -180f..180f, 1f, 0f, "Mask rotation")
            AeCheckbox(c.params[6] != 0f, { on -> vm.setMask(maskIndex, "Invert radial") { m -> m.withParam(component, 6, if (on) 1f else 0f) } }, "Outside the shape")
        }
        MaskKind.LUMINANCE -> {
            param("Shadows edge", 0, 0f..100f, 100f, 0f, "Luminance range")
            param("Highlights edge", 1, 0f..100f, 100f, 100f, "Luminance range")
            param("Smoothness", 2, 0f..100f, 100f, 10f, "Luminance smoothness")
        }
        MaskKind.COLOR -> {
            Box(Modifier.size(20.dp).background(Color(c.params[0], c.params[1], c.params[2]), Ae.shapes.chip))
            param("Tolerance", 3, 0f..100f, 100f, 10f, "Colour tolerance")
            param("Feather", 4, 0f..100f, 100f, 10f, "Colour feather")
        }
        MaskKind.AI -> AeText(
            "From the picture as it was shown when you added it (${c.bitmap?.label.orEmpty()}). If you change the crop, add it again.",
            color = Ae.colors.textSecondary,
        )
        else -> Unit
    }
}

private fun LocalMask.withParam(component: Int, at: Int, value: Float): LocalMask = copy(
    components = components.mapIndexed { k, x ->
        if (k == component) x.copy(params = x.params.toMutableList().also { it[at] = value }) else x
    },
)

/** A one-line text prompt for renaming a mask; [onDone] gets the trimmed name or null when cancelled. */
@Composable
private fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val c = Ae.colors
    val valid = text.isNotBlank()
    AeDialog(
        title = "Rename mask",
        body = "Give the mask a name you will recognise.",
        onDismiss = { onDone(null) },
        onConfirm = { if (valid) onDone(text.trim().take(40)) },
        actions = {
            AeButton("Cancel", { onDone(null) })
            AeButton("Rename", { onDone(text.trim().take(40)) }, style = AeButtonStyle.Primary, enabled = valid)
        },
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it.take(40) },
            singleLine = true,
            textStyle = Ae.type.body.copy(color = c.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(Ae.density.controlHeight)
                .background(c.surface3, Ae.shapes.control)
                .border(1.dp, c.accent, Ae.shapes.control)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MasksSection(s: DevelopSettings, vm: DevelopViewModel) {
    val selected by vm.selectedMask.collectAsStateWithLifecycle()
    val overlayOn by vm.maskOverlayVisible.collectAsStateWithLifecycle()
    AeText("Add", color = Ae.colors.textSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MaskKind.MANUAL.forEach { kind ->
            AeButton(kind.label, { vm.addMask(kind) }, style = AeButtonStyle.Secondary, enabled = s.masks.size < LocalMask.MAX_MASKS)
        }
    }
    AeText("From the picture (AI)", color = Ae.colors.textSecondary)
    val aiBusy by vm.aiBusy.collectAsStateWithLifecycle()
    val aiNotice by vm.aiNotice.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AiMaskSource.entries.forEach { source ->
            AeButton(
                if (aiBusy) "${source.label}…" else source.label,
                { vm.addAiMask(source) },
                style = AeButtonStyle.Secondary,
                enabled = s.masks.size < LocalMask.MAX_MASKS && !aiBusy,
            )
        }
    }
    aiNotice?.let { AeText(it, color = Ae.colors.danger) }
    val aiPrompt by vm.aiPrompt.collectAsStateWithLifecycle()
    val aiModels by vm.aiModels.collectAsStateWithLifecycle()
    aiPrompt?.let { prompt ->
        app.croptop.core.designsystem.component.AiDownloadDialog(
            featureName = "${prompt.source.label} mask",
            modelIds = prompt.models.map { it.id },
            state = aiModels,
            onInstall = vm::installAiModel,
            onCancel = vm::cancelAiModel,
            onDismiss = vm::dismissAiPrompt,
            onReady = vm::retryAiPrompt,
        )
    }
    s.masks.forEachIndexed { i, mask ->
        ListRow(mask.name, selected = selected == i, dim = !mask.enabled) { vm.selectMask(i) }
    }
    val tool by vm.maskTool.collectAsStateWithLifecycle()
    val index = selected?.takeIf { it in s.masks.indices } ?: return
    AeText("On the preview", color = Ae.colors.textSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(MaskTool.HANDLES to "Handles", MaskTool.BRUSH to "Brush", MaskTool.ERASE to "Erase", MaskTool.PICK to "Pick colour").forEach { (t, label) ->
            AeButton(label, { vm.maskTool.value = t }, style = if (tool == t) AeButtonStyle.Primary else AeButtonStyle.Secondary)
        }
    }
    val mask = s.masks[index]
    var renaming by remember(index) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AeText(mask.name, modifier = Modifier.weight(1f))
        AeButton("Rename", { renaming = true }, style = AeButtonStyle.Ghost)
    }
    if (renaming) {
        RenameDialog(mask.name, onDone = { name ->
            renaming = false
            if (name != null && name != mask.name) vm.setMask(index, "Rename mask") { it.copy(name = name) }
        })
    }
    AeCheckbox(mask.enabled, { on -> vm.setMask(index, "Mask visibility") { it.copy(enabled = on) } }, "Enabled")
    AeCheckbox(mask.invert, { on -> vm.setMask(index, "Invert mask") { it.copy(invert = on) } }, "Invert")
    AeCheckbox(overlayOn, { on -> vm.maskOverlayVisible.value = on }, "Show overlay (O)")
    val overlayColor by vm.maskOverlayColor.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AeText("Overlay colour", color = Ae.colors.textSecondary, modifier = Modifier.weight(1f))
        AeDropdown(DevelopViewModel.MASK_OVERLAY_COLORS, DevelopViewModel.MASK_OVERLAY_COLORS.firstOrNull { it.second == overlayColor }
            ?: DevelopViewModel.MASK_OVERLAY_COLORS.first(), { vm.maskOverlayColor.value = it.second }, { it.first })
    }
    if (tool == MaskTool.BRUSH || tool == MaskTool.ERASE) {
        val size by vm.brushSize.collectAsStateWithLifecycle()
        val feather by vm.brushFeather.collectAsStateWithLifecycle()
        val flow by vm.brushFlow.collectAsStateWithLifecycle()
        AeSlider("Brush size", size * 1000f, { vm.brushSize.value = (it / 1000f).coerceIn(0.0005f, 1f) }, range = 1f..300f, defaultValue = 50f)
        AeSlider("Brush feather", feather * 100f, { vm.brushFeather.value = it / 100f }, range = 0f..100f, defaultValue = 50f)
        AeSlider("Brush flow", flow * 100f, { vm.brushFlow.value = it / 100f }, range = 1f..100f, defaultValue = 100f)
    }
    AeSlider(
        "Amount",
        mask.amount * 100f,
        { v -> vm.previewMask(index) { it.copy(amount = v / 100f) } },
        range = 0f..100f,
        defaultValue = 100f,
        onValueChangeFinished = { vm.commit("Mask amount") },
    )
    @Composable
    fun tone(label: String, value: Float, historyLabel: String, apply: (BasicTone, Float) -> BasicTone) = AeSlider(
        label,
        value,
        { v -> vm.previewMask(index) { it.copy(adjust = apply(it.adjust, v)) } },
        range = -100f..100f,
        defaultValue = 0f,
        onValueChangeFinished = { vm.commit(historyLabel) },
    )
    tone("Exposure", mask.adjust.exposure * 100f / 5f, "Mask exposure") { t, v -> t.copy(exposure = v * 5f / 100f) }
    tone("Contrast", mask.adjust.contrast, "Mask contrast") { t, v -> t.copy(contrast = v) }
    tone("Highlights", mask.adjust.highlights, "Mask highlights") { t, v -> t.copy(highlights = v) }
    tone("Shadows", mask.adjust.shadows, "Mask shadows") { t, v -> t.copy(shadows = v) }
    tone("Whites", mask.adjust.whites, "Mask whites") { t, v -> t.copy(whites = v) }
    tone("Blacks", mask.adjust.blacks, "Mask blacks") { t, v -> t.copy(blacks = v) }
    tone("Texture", mask.adjust.texture, "Mask texture") { t, v -> t.copy(texture = v) }
    tone("Clarity", mask.adjust.clarity, "Mask clarity") { t, v -> t.copy(clarity = v) }
    tone("Dehaze", mask.adjust.dehaze, "Mask dehaze") { t, v -> t.copy(dehaze = v) }
    tone("Vibrance", mask.adjust.vibrance, "Mask vibrance") { t, v -> t.copy(vibrance = v) }
    tone("Saturation", mask.adjust.saturation, "Mask saturation") { t, v -> t.copy(saturation = v) }
    AeText("Components", color = Ae.colors.textSecondary)
    mask.components.forEachIndexed { c, component ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AeText(component.kind.label, color = Ae.colors.textSecondary, modifier = Modifier.weight(1f))
            if (c > 0) {
                AeDropdown(MaskOp.entries, component.op, { op ->
                    vm.setMask(index, "Mask operation") { m ->
                        m.copy(components = m.components.mapIndexed { k, x -> if (k == c) x.copy(op = op) else x })
                    }
                }, { it.label })
            }
            AeButton("Remove", {
                vm.setMask(index, "Remove mask component") { m -> m.copy(components = m.components.filterIndexed { k, _ -> k != c }) }
            }, style = AeButtonStyle.Ghost)
        }
        MaskComponentControls(index, c, component, vm)
    }
    if (mask.components.size < LocalMask.MAX_COMPONENTS) {
        AeText("Add component", color = Ae.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MaskKind.MANUAL.forEach { kind ->
                AeButton(kind.label, {
                    val component = LocalMaskTemplates.of(kind, 1).components.first()
                    vm.setMask(index, "Add ${kind.label} component") { m -> m.copy(components = m.components + component) }
                }, style = AeButtonStyle.Secondary)
            }
        }
    }
    AeButton("Delete mask", { vm.deleteMask(index) }, style = AeButtonStyle.Ghost)
}

@Composable
private fun Section(title: String, initiallyOpen: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    AePanel(title, expanded = open, onExpandedChange = { open = it }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Paste settings with a section checklist (copy/paste/sync). */
@Composable
private fun PasteDialog(onPaste: (Set<DevelopSection>) -> Unit, onCancel: () -> Unit) = SectionDialog(
    title = "Paste Settings",
    body = "Choose which settings to apply to this photo.",
    confirm = "Paste",
    initial = DevelopSection.entries.toSet() - DevelopSection.CROP - DevelopSection.SPOTS,
    onConfirm = onPaste,
    onCancel = onCancel,
)

/** A checklist of Develop sections with one confirm action. */
@Composable
private fun SectionDialog(
    title: String,
    body: String,
    confirm: String,
    initial: Set<DevelopSection>,
    onConfirm: (Set<DevelopSection>) -> Unit,
    onCancel: () -> Unit,
) {
    var chosen by remember { mutableStateOf(initial) }
    AeDialog(
        title = title,
        body = body,
        onDismiss = onCancel,
        onConfirm = { onConfirm(chosen) },
        actions = {
            AeButton("Cancel", onCancel)
            AeButton(confirm, { onConfirm(chosen) }, style = AeButtonStyle.Primary, enabled = chosen.isNotEmpty())
        },
    ) {
        Column {
            DevelopSection.entries.forEach { sec ->
                AeCheckbox(sec in chosen, { on -> chosen = if (on) chosen + sec else chosen - sec }, sec.label)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private val TempTrack = Brush.horizontalGradient(listOf(Color(0xFF4A7BD6), Color(0xFFE8E2D0), Color(0xFFE0B63A)))
private val TintTrack = Brush.horizontalGradient(listOf(Color(0xFF3FA66A), Color(0xFFD0D0D0), Color(0xFFC94FB8)))

private val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
