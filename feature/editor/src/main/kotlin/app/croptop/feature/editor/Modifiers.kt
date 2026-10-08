package app.croptop.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
internal fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
