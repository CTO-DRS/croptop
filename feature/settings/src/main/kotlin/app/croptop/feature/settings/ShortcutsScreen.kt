package app.croptop.feature.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.croptop.core.designsystem.component.AeAppHeader
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeKbd
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.ActionCategory
import app.croptop.core.input.ActionDef
import app.croptop.core.input.Actions
import app.croptop.core.input.FuzzyMatch
import app.croptop.core.input.KeyChord
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutPreset
import app.croptop.core.input.toChord
import kotlinx.serialization.Serializable

@Serializable
data object ShortcutsRoute : NavKey

private val ModifierKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_CTRL_LEFT, AndroidKeyEvent.KEYCODE_CTRL_RIGHT, AndroidKeyEvent.KEYCODE_SHIFT_LEFT,
    AndroidKeyEvent.KEYCODE_SHIFT_RIGHT, AndroidKeyEvent.KEYCODE_ALT_LEFT, AndroidKeyEvent.KEYCODE_ALT_RIGHT,
    AndroidKeyEvent.KEYCODE_META_LEFT, AndroidKeyEvent.KEYCODE_META_RIGHT, AndroidKeyEvent.KEYCODE_FUNCTION,
)

/** Keyboard shortcut editor. */
@Composable
fun ShortcutsScreen(onBack: () -> Unit, viewModel: ShortcutsViewModel = hiltViewModel()) {
    val km by viewModel.keymap.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<ActionDef?>(null) }
    var pending by remember { mutableStateOf<Pair<ActionDef, KeyChord>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val dispatcher = LocalActionDispatcher.current
    val modified = remember(km) { km.differencesFrom(viewModel.defaults) }
    val rows = remember(query) { if (query.isBlank()) Actions.all else FuzzyMatch.rank(query, Actions.all) { it.label + " " + it.category.label } }

    // Shortcuts must not fire while editing them.
    DisposableEffect(Unit) {
        dispatcher.textInputActive = true
        onDispose { dispatcher.textInputActive = false }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportJson().toByteArray()) }
            notice = "Keymap exported"
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            notice = text?.let(viewModel::import) ?: "Keymap imported"
        }
    }

    val c = Ae.colors
    Column(Modifier.fillMaxSize().background(c.app)) {
        AeAppHeader(startPadding = 8.dp) {
            AeIconButton(AeIcons.Close, "Back to settings", onBack)
            AeText("Keyboard Shortcuts", style = Ae.type.title, modifier = Modifier.weight(1f))
            AeButton("Import…", { importer.launch(arrayOf("application/json", "text/plain")) }, style = AeButtonStyle.Ghost)
            AeButton("Export…", { exporter.launch("croptop-keymap.json") }, style = AeButtonStyle.Ghost)
            AeButton("Reset All", { viewModel.resetAll() }, enabled = modified.isNotEmpty())
        }
        AeDivider()
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 820.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchField(query) { query = it }
                AeText(
                    notice ?: "Click a shortcut, then press the new keys. Photoshop-compatible by default.",
                    style = Ae.type.caption,
                    color = if (notice != null) c.textPrimary else c.textTertiary,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeText("Preset", style = Ae.type.caption, color = c.textSecondary)
                    AeDropdown(
                        ShortcutPreset.entries,
                        viewModel.presetFor(km),
                        { preset ->
                            viewModel.applyPreset(preset)
                            if (preset != ShortcutPreset.CUSTOM) notice = "Applied ${preset.label} shortcut preset"
                        },
                        ShortcutPreset::label,
                    )
                    AeText(
                        "Affinity maps matching actions where shortcuts do not collide; other bindings stay as set.",
                        style = Ae.type.caption,
                        color = c.textTertiary,
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    var lastCategory: ActionCategory? = null
                    rows.forEach { def ->
                        if (query.isBlank() && def.category != lastCategory) {
                            lastCategory = def.category
                            item(key = "h-${def.category}") {
                                AeText(
                                    def.category.label.uppercase(),
                                    style = Ae.type.panelHeader,
                                    color = c.textSecondary,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                                )
                            }
                        }
                        item(key = def.id.id) {
                            ShortcutRow(
                                def = def,
                                chords = km.chordsFor(def.id),
                                modified = def.id in modified,
                                onRecord = { recording = def },
                                onReset = { viewModel.reset(def.id) },
                                onClear = { viewModel.clear(def.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    recording?.let { def ->
        RecordDialog(
            def = def,
            onChord = { chord ->
                recording = null
                if (km.actionsUsing(chord, def.id).isEmpty()) viewModel.assign(def.id, chord) else pending = def to chord
            },
            onCancel = { recording = null },
        )
    }
    pending?.let { (def, chord) ->
        val others = km.actionsUsing(chord, def.id).mapNotNull { Actions[it]?.label }
        AeDialog(
            title = "${chord.format()} is already used",
            body = "It's assigned to ${others.joinToString()}. Reassign it to “${def.label}”?",
            onDismiss = { pending = null },
            onConfirm = {
                viewModel.assign(def.id, chord)
                pending = null
            },
            actions = {
                AeButton("Cancel", { pending = null })
                AeButton(
                    "Reassign",
                    {
                        viewModel.assign(def.id, chord)
                        pending = null
                    },
                    style = AeButtonStyle.Primary,
                )
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val c = Ae.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(c.surface2, Ae.shapes.control)
            .border(1.dp, c.lineHairline, Ae.shapes.control)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) AeText("Search commands…", color = c.textTertiary)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = Ae.type.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search commands" },
        )
    }
}

@Composable
private fun ShortcutRow(
    def: ActionDef,
    chords: List<KeyChord>,
    modified: Boolean,
    onRecord: () -> Unit,
    onReset: () -> Unit,
    onClear: () -> Unit,
) {
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(if (hovered) c.surface1 else Color.Transparent, Ae.shapes.control).hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AeText(def.label, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (modified) AeText("Modified", style = Ae.type.micro, color = c.accent, modifier = Modifier.padding(end = 8.dp))
        Box(
            Modifier
                .width(200.dp)
                .height(28.dp)
                .background(c.surface2, Ae.shapes.control)
                .border(1.dp, c.lineHairline, Ae.shapes.control)
                .clickable(onClickLabel = "Change shortcut for ${def.label}", onClick = onRecord)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (chords.isEmpty()) {
                AeText("Add shortcut", style = Ae.type.caption, color = c.textTertiary)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chords.forEach { AeKbd(it.format()) } }
            }
        }
        // Row actions appear on hover (or when modified) so the list stays calm.
        val show = hovered || modified
        Box(Modifier.alpha(if (show) 1f else 0f)) {
            AeIconButton(AeIcons.Undo, "Reset to default", onReset, enabled = modified && show)
        }
        Box(Modifier.alpha(if (show) 1f else 0f)) {
            AeIconButton(AeIcons.Close, "Remove shortcut", onClear, enabled = chords.isNotEmpty() && show)
        }
    }
}

/** Captures the next key chord (modifiers + key). Esc alone cancels. */
@Composable
private fun RecordDialog(def: ActionDef, onChord: (KeyChord) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AeDialog(
        title = "Press the new shortcut",
        body = "For “${def.label}”. Press Esc to cancel.",
        onDismiss = onCancel,
        actions = { AeButton("Cancel", onCancel) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Ae.colors.surface3, Ae.shapes.control)
                .border(1.dp, Ae.colors.accent, Ae.shapes.control)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val native = e.nativeKeyEvent
                    when {
                        native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE &&
                            !native.hasModifiers(
                                AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_ALT_ON or AndroidKeyEvent.META_SHIFT_ON,
                            ) -> onCancel()

                        native.keyCode in ModifierKeyCodes -> Unit

                        else -> onChord(native.toChord())
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            AeText("Waiting for keys…", color = Ae.colors.textSecondary)
        }
    }
}
