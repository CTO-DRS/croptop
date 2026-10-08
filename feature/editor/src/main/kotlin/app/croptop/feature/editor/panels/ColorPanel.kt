package app.croptop.feature.editor.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeColorWell
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeMenu
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.AeTooltipArea
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.shortcutLabel
import app.croptop.core.model.ColorMath
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.session.ColorState

/** Default swatches: neutrals, then a hue sweep at two brightness levels. */
val DefaultSwatches: List<RgbaColor> = buildList {
    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { add(RgbaColor(it, it, it)) }
    (0 until 12).forEach { add(ColorMath.fromHsv(app.croptop.core.model.Hsv(it * 30f, 0.85f, 0.95f))) }
    (0 until 12).forEach { add(ColorMath.fromHsv(app.croptop.core.model.Hsv(it * 30f, 0.7f, 0.55f))) }
}

/** Color panel: FG/BG wells, picker, harmonies, recent colors and swatches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPanelContent(
    colors: ColorState,
    onForeground: (RgbaColor) -> Unit,
    onBackground: (RgbaColor) -> Unit,
    onCommit: (RgbaColor) -> Unit,
    onSwap: () -> Unit,
    onDefaults: () -> Unit,
    swatches: SwatchesUi = SwatchesUi(DefaultSwatchList),
    onActionComplete: (String) -> Unit = {},
) {
    var editingBackground by rememberSaveable { mutableStateOf(false) }
    var harmony by rememberSaveable { mutableStateOf(ColorMath.Harmony.COMPLEMENTARY) }
    val active = if (editingBackground) colors.background else colors.foreground
    val setActive = if (editingBackground) onBackground else onForeground
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Photoshop-style overlapping wells: foreground front-left, background behind.
            Box(Modifier.size(maxOf(30.dp, Ae.density.controlHeight) + 20.dp)) {
                AeColorWell(colors.background, "Background color", Modifier.offset(20.dp, 20.dp), size = 30) {
                    editingBackground = true
                    onActionComplete("Edit background color")
                }
                AeColorWell(colors.foreground, "Foreground color", size = 30) {
                    editingBackground = false
                    onActionComplete("Edit foreground color")
                }
            }
            Column {
                AeText(
                    if (editingBackground) "Editing background" else "Editing foreground",
                    style = Ae.type.caption,
                    color = Ae.colors.textSecondary,
                )
                Row {
                    AeIconButton(AeIcons.SwapColors, "Swap colors", {
                        onSwap()
                        onActionComplete("Swap foreground and background colors")
                    }, shortcut = shortcutLabel("color.swap"))
                    AeIconButton(AeIcons.Undo, "Default colors", {
                        onDefaults()
                        onActionComplete("Restore default colors")
                    }, shortcut = shortcutLabel("color.default"))
                }
            }
        }
        AeColorPicker(
            active,
            { setActive(it) },
            previous = colors.recent.firstOrNull(),
            onEditComplete = { onActionComplete("Set ${if (editingBackground) "background" else "foreground"} color") },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AeDropdown(ColorMath.Harmony.entries, harmony, {
                harmony = it
                onActionComplete("Set color harmony to ${it.label.lowercase()}")
            }, { it.label })
            ColorMath.harmony(active, harmony).forEach { h ->
                AeColorWell(h, harmony.label, size = 22) {
                    setActive(h)
                    onCommit(h)
                    onActionComplete("Set color harmony value")
                }
            }
        }
        if (colors.recent.isNotEmpty()) {
            SectionLabel("Recent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                colors.recent.forEach { r ->
                    AeColorWell(r, "Recent color", size = 20) {
                        setActive(r)
                        onActionComplete("Set ${if (editingBackground) "background" else "foreground"} color")
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Swatches", Modifier.weight(1f))
            AeIconButton(AeIcons.Add, "Add ${if (editingBackground) "background" else "foreground"} color to swatches", { swatches.onAdd(active) })
            Box {
                var menu by remember { mutableStateOf(false) }
                AeIconButton(AeIcons.More, "Swatch options", { menu = true })
                AeMenu(
                    menu,
                    { menu = false },
                    listOf(
                        AeMenuEntry.Item("Import Swatches (.aco, .ase)…", { swatches.onImport() }),
                        AeMenuEntry.Item("Export Swatches (.ase)…", { swatches.onExport() }),
                        AeMenuEntry.Divider,
                        AeMenuEntry.Item("Reset to Default Swatches", { swatches.onReset() }),
                    ),
                )
            }
        }
        swatches.message?.let { AeText(it, style = Ae.type.caption, color = Ae.colors.textSecondary) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            swatches.swatches.forEachIndexed { i, sw ->
                AeContextMenuArea({
                    listOf(
                        AeMenuEntry.Header(sw.name),
                        AeMenuEntry.Item("Set as Background Color", {
                            onBackground(sw.color)
                            onActionComplete("Set background color")
                        }),
                        AeMenuEntry.Item("Delete Swatch", { swatches.onRemove(i) }),
                    )
                }) {
                    AeTooltipArea(sw.name) {
                        AeColorWell(sw.color, sw.name, size = 20) {
                            setActive(sw.color)
                            onCommit(sw.color)
                            onActionComplete("Set ${if (editingBackground) "background" else "foreground"} color")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    AeText(text.uppercase(), style = Ae.type.panelHeader, color = Ae.colors.textTertiary, modifier = modifier)
