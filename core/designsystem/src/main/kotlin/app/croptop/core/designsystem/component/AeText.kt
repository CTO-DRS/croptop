package app.croptop.core.designsystem.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.croptop.core.designsystem.theme.Ae

/** Chrome text. Defaults to body / primary text color, single line with ellipsis. */
@Composable
fun AeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Ae.type.body,
    color: Color = Ae.colors.textPrimary,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
