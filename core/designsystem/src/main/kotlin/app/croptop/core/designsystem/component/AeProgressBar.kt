package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae

/** Determinate progress bar for downloads and queues; [fraction] is 0..1 and the label is what a screen reader announces. */
@Composable
fun AeProgressBar(fraction: Float, contentDescription: String, modifier: Modifier = Modifier) {
    val c = Ae.colors
    val value = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(c.surface3, Ae.shapes.control)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value).background(c.accent, Ae.shapes.control))
    }
}
