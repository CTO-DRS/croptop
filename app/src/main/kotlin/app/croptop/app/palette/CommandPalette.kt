package app.croptop.app.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.agent.AskPlan
import app.croptop.core.agent.AskService
import app.croptop.core.agent.PlannedStep
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeKbd
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.Actions
import app.croptop.core.input.FuzzyMatch
import kotlinx.coroutines.launch

/** One palette row: a command or a recent document. */
data class PaletteItem(val label: String, val category: String, val shortcut: String? = null, val run: () -> Unit)

/**
 * Ctrl+K command palette. Lists only commands that work on the current screen,
 * so nothing it offers is a dead end.
 */
@Composable
fun CommandPalette(
    dispatcher: ActionDispatcher,
    onDismiss: () -> Unit,
    extra: List<PaletteItem> = emptyList(),
    ask: AskService? = null,
) {
    var query by remember { mutableStateOf("") }
    var askState by remember { mutableStateOf<AskState?>(null) }
    val scope = rememberCoroutineScope()
    var highlighted by remember { mutableIntStateOf(0) }
    val available = remember(extra) {
        Actions.all.filter { it.id.id != "app.palette" && dispatcher.canPerform(it.id) }.map { def ->
            PaletteItem(def.label, def.category.label, dispatcher.shortcuts.label(def.id)) { dispatcher.perform(def.id) }
        } + extra
    }
    val results = remember(query, available, ask) {
        val ranked = FuzzyMatch.rank(query.removePrefix("?").trim(), available) { it.label }.take(50)
        val text = query.removePrefix("?").trim()
        if (ask == null || text.isBlank()) {
            ranked
        } else {
            // "?" first makes Ask the top result; otherwise it trails the commands.
            val item = PaletteItem("Ask: “$text”", "Assistant") {
                askState = AskState.Planning(text)
                scope.launch {
                    askState = when (val p = ask.planner.plan(ask.model!!, text)) {
                        is AskPlan.Steps -> AskState.Review(text, p.steps)
                        is AskPlan.Failed -> AskState.Message(p.message)
                    }
                }
            }
            if (query.startsWith("?")) listOf(item) + ranked else ranked + item
        }
    }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        highlighted = 0
        askState = null // editing the request returns to the results
    }
    LaunchedEffect(highlighted) { if (results.isNotEmpty()) list.animateScrollToItem(highlighted) }
    DisposableEffect(Unit) {
        dispatcher.textInputActive = true
        onDispose { dispatcher.textInputActive = false }
    }

    fun run(item: PaletteItem) {
        if (item.category == "Assistant") {
            item.run()
            return
        }
        onDismiss()
        item.run()
    }

    fun applyPlan(steps: List<PlannedStep>) {
        val service = ask ?: return
        scope.launch { askState = AskState.Message(service.planner.apply(steps)) }
    }

    val c = Ae.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 96.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(c.surface2, Ae.shapes.popover)
                .border(1.dp, c.lineStrong, Ae.shapes.popover)
                // Swallow clicks so tapping the card doesn't dismiss.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AeIcons.Search, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) AeText("Search commands…", color = c.textTertiary)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Ae.type.body.copy(color = c.textPrimary),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = "Search commands" }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown -> {
                                        highlighted = (highlighted + 1).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                                        true
                                    }

                                    Key.DirectionUp -> {
                                        highlighted = (highlighted - 1).coerceAtLeast(0)
                                        true
                                    }

                                    Key.Enter, Key.NumPadEnter -> {
                                        when (val a = askState) {
                                            is AskState.Review -> applyPlan(a.steps)
                                            is AskState.Message -> onDismiss()
                                            is AskState.Planning -> Unit
                                            null -> results.getOrNull(highlighted)?.let(::run)
                                        }
                                        true
                                    }

                                    Key.Escape -> {
                                        onDismiss()
                                        true
                                    }

                                    else -> false
                                }
                            },
                    )
                }
                AeKbd("Esc")
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineHairline))
            val asking = askState
            if (asking != null) {
                AskPanel(asking, onApply = ::applyPlan, onClose = onDismiss)
            } else if (results.isEmpty()) {
                AeText("No matching commands", color = c.textTertiary, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(vertical = 4.dp), state = list) {
                    itemsIndexed(results, key = { _, d -> d.category + d.label }) { i, def ->
                        val active = i == highlighted
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .height(36.dp)
                                .background(if (active) c.accent else Color.Transparent, Ae.shapes.chip)
                                .clickable(remember { MutableInteractionSource() }, indication = null) { run(def) }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AeText(def.label, color = if (active) c.onAccent else c.textPrimary, modifier = Modifier.weight(1f))
                            AeText(def.category, style = Ae.type.caption, color = if (active) c.onAccent else c.textTertiary)
                            def.shortcut?.let { AeKbd(it) }
                        }
                    }
                }
            }
        }
    }
}

/** Palette states while the assistant plans. */
private sealed interface AskState {
    data class Planning(val request: String) : AskState

    data class Review(val request: String, val steps: List<PlannedStep>) : AskState

    data class Message(val text: String) : AskState
}

/** Shows the plan before anything runs: every step, why, and one Apply (Enter) / Cancel (Esc). */
@Composable
private fun AskPanel(state: AskState, onApply: (List<PlannedStep>) -> Unit, onClose: () -> Unit) {
    val c = Ae.colors
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is AskState.Planning -> AeText("Planning “${state.request}” on this device…", color = c.textSecondary)

            is AskState.Review -> {
                AeText("The assistant will:", style = Ae.type.label, color = c.textSecondary)
                state.steps.forEachIndexed { i, s ->
                    AeText("${i + 1}. ${s.why.ifBlank { s.tool.replace('_', ' ') }}", maxLines = 2)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    AeButton("Cancel", onClose)
                    AeButton("Apply", { onApply(state.steps) }, style = AeButtonStyle.Primary)
                }
            }

            is AskState.Message -> {
                AeText(state.text, maxLines = 3)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { AeButton("Close", onClose) }
            }
        }
    }
}
