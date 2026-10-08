package app.croptop.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeKbd
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae

/** Keyboard layout choice offered on first run ("Coming from Photoshop?"). */
enum class KeymapChoice(val title: String, val body: String) {
    PHOTOSHOP("Photoshop-compatible", "V, B, M, Ctrl+J, Ctrl+T… work exactly as you're used to."),
    SIMPLE("Keep it simple", "Same shortcuts, but single-letter tool keys are turned off until you enable them."),
}

private data class Step(val icon: ImageVector, val title: String, val body: String)

private val Steps = listOf(
    Step(AeIcons.Image, "Welcome to CropTop", "Layers, masks, retouching and RAW development — built for this laptop's keyboard, trackpad and GPU."),
    Step(AeIcons.Command, "Coming from Photoshop?", "Pick how the keyboard should behave. You can change any shortcut later in Settings."),
    Step(
        AeIcons.Search,
        "Everything is one search away",
        "Press Ctrl+K to find any command. Drop images anywhere to open them, or add folders to the Library to rate and cull.",
    ),
)

/**
 * First-run welcome: 3 short steps, skippable at any time, shown once per major version.
 */
@Composable
fun WelcomeDialog(onFinish: (KeymapChoice) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var choice by rememberSaveable { mutableStateOf(KeymapChoice.PHOTOSHOP) }
    val s = Steps[step]
    val last = step == Steps.lastIndex
    AeDialog(
        title = s.title,
        body = s.body,
        onDismiss = { onFinish(choice) },
        onConfirm = { if (last) onFinish(choice) else step++ },
        width = 480.dp,
        actions = {
            // Progress dots.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Steps.indices.forEach { i ->
                    Box(Modifier.size(6.dp).background(if (i == step) Ae.colors.accent else Ae.colors.surface4, Ae.shapes.chip))
                }
            }
            Spacer(Modifier.weight(1f))
            if (!last) AeButton("Skip", { onFinish(choice) }, style = AeButtonStyle.Ghost)
            if (step > 0) AeButton("Back", { step-- })
            AeButton(if (last) "Start editing" else "Next", { if (last) onFinish(choice) else step++ }, style = AeButtonStyle.Primary)
        },
    ) {
        when (step) {
            0 -> Hero(s.icon)

            1 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeymapChoice.entries.forEach { k -> ChoiceCard(k, k == choice) { choice = k } }
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tip("Search commands", "Ctrl+K")
                Tip("Open an image", "Ctrl+O")
                Tip("See all shortcuts", "Meta+/")
            }
        }
    }
}

@Composable
private fun Hero(icon: ImageVector) {
    Box(
        Modifier.fillMaxWidth().height(120.dp).background(Ae.colors.accentMuted, Ae.shapes.popover),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(56.dp).background(Ae.colors.accent, Ae.shapes.popover), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Ae.colors.onAccent, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ChoiceCard(k: KeymapChoice, selected: Boolean, onClick: () -> Unit) {
    val c = Ae.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.accentMuted else c.surface3, Ae.shapes.control)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.lineHairline, Ae.shapes.control)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(16.dp).border(2.dp, if (selected) c.accent else c.lineStrong, Ae.shapes.popover)
                .padding(3.dp).background(if (selected) c.accent else Color.Transparent, Ae.shapes.popover),
        )
        Column(Modifier.weight(1f)) {
            AeText(k.title, style = Ae.type.label)
            AeText(k.body, style = Ae.type.caption, color = c.textSecondary, maxLines = 2)
        }
    }
}

@Composable
private fun Tip(text: String, keys: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AeText(text, modifier = Modifier.weight(1f))
        AeKbd(keys)
        Spacer(Modifier.width(2.dp))
    }
}
