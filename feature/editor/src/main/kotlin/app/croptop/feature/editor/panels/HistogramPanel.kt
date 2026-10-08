package app.croptop.feature.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.Histogram
import app.croptop.core.engine.api.HistogramChannel
import app.croptop.core.engine.api.HistogramChannelStats
import app.croptop.core.engine.api.HistogramScope
import java.util.Locale

private val histogramChannels = listOf(
    HistogramChannel.RGB,
    HistogramChannel.RED,
    HistogramChannel.GREEN,
    HistogramChannel.BLUE,
    HistogramChannel.LUMINOSITY,
)

private fun HistogramChannel.label(): String = when (this) {
    HistogramChannel.RGB -> "RGB"
    HistogramChannel.RED -> "R"
    HistogramChannel.GREEN -> "G"
    HistogramChannel.BLUE -> "B"
    HistogramChannel.LUMINOSITY -> "L"
}

private fun HistogramChannel.color(): Color = when (this) {
    HistogramChannel.RGB -> Color(0xFFE8E8EA)
    HistogramChannel.RED -> Color(0xFFFF6868)
    HistogramChannel.GREEN -> Color(0xFF66D18A)
    HistogramChannel.BLUE -> Color(0xFF6EA8FF)
    HistogramChannel.LUMINOSITY -> Color(0xFFFFD166)
}

internal fun histogramStatsLabel(stats: HistogramChannelStats): String =
    "${formatPixelCount(stats.weightedPixels)} px · μ ${formatNumber(stats.mean)} · σ ${formatNumber(stats.standardDeviation)} · med ${stats.median}"

private fun formatPixelCount(value: Double): String = when {
    value >= 1_000_000 -> "${formatNumber(value / 1_000_000)}M"
    value >= 10_000 -> "${formatNumber(value / 1_000)}k"
    value >= 1_000 -> "${formatNumber(value / 1_000)}k"
    else -> formatNumber(value)
}

private fun formatNumber(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** Live histogram panel. The parent retains one engine flow per selected scope across recompositions. */
@Composable
fun HistogramPanelContent(
    histogram: Histogram?,
    scope: HistogramScope,
    availableScopes: List<HistogramScope>,
    onScopeChange: (HistogramScope) -> Unit,
) {
    var visibleChannels by remember { mutableStateOf(setOf(HistogramChannel.RGB)) }
    var expandedChart by remember { mutableStateOf(false) }
    val stats = histogram?.statistics.orEmpty()
    val hasPixels = stats[HistogramChannel.RGB]?.weightedPixels?.let { it > 0.0 } == true
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText("Source", color = Ae.colors.textSecondary)
            AeDropdown(
                options = availableScopes,
                selected = scope,
                onSelect = onScopeChange,
                label = { it.label() },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            histogramChannels.forEach { channel ->
                val selected = channel in visibleChannels
                val description = "${channel.longLabel()} histogram ${if (selected) "visible" else "hidden"}"
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) Ae.colors.surface4 else Color.Transparent)
                        .toggleable(selected, role = Role.Checkbox) { checked ->
                            visibleChannels = if (checked) visibleChannels + channel else visibleChannels - channel
                        }
                        .semantics {
                            contentDescription = description
                            role = Role.Checkbox
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AeText(channel.label(), color = channel.color())
                }
            }
        }
        if (histogram == null) {
            Box(Modifier.fillMaxWidth().height(if (expandedChart) 176.dp else 96.dp), contentAlignment = Alignment.Center) {
                AeText("Waiting for histogram data", color = Ae.colors.textSecondary)
            }
        } else if (!hasPixels) {
            Box(Modifier.fillMaxWidth().height(if (expandedChart) 176.dp else 96.dp), contentAlignment = Alignment.Center) {
                AeText("No pixels in this source", color = Ae.colors.textSecondary)
            }
        } else {
            HistogramChart(histogram, visibleChannels, expandedChart)
        }
        statsChannels(visibleChannels).forEach { channel ->
            stats[channel]?.let { value ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    AeText(channel.longLabel(), color = channel.color())
                    AeText(histogramStatsLabel(value), color = Ae.colors.textSecondary, maxLines = 2)
                }
            }
        }
        AeButton(
            text = if (expandedChart) "Compact histogram" else "Expand histogram",
            onClick = { expandedChart = !expandedChart },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun HistogramScope.label(): String = when (this) {
    HistogramScope.MERGED -> "Merged image"
    HistogramScope.ACTIVE_LAYER -> "Active layer"
    HistogramScope.SELECTION -> "Selection"
}

private fun HistogramChannel.longLabel(): String = when (this) {
    HistogramChannel.RGB -> "RGB"
    HistogramChannel.RED -> "Red"
    HistogramChannel.GREEN -> "Green"
    HistogramChannel.BLUE -> "Blue"
    HistogramChannel.LUMINOSITY -> "Luminosity"
}

private fun statsChannels(visible: Set<HistogramChannel>): List<HistogramChannel> =
    histogramChannels.filter { it in visible }

@Composable
private fun HistogramChart(histogram: Histogram, visible: Set<HistogramChannel>, expanded: Boolean) {
    val colors = Ae.colors
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(if (expanded) 176.dp else 96.dp)
            .semantics { contentDescription = "${visible.joinToString { it.longLabel() }} histogram graph" },
    ) {
        val left = 1.dp.toPx()
        val right = size.width - left
        val top = 4.dp.toPx()
        val bottom = size.height - 4.dp.toPx()
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            drawLine(colors.lineHairline, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        val series = buildList {
            if (HistogramChannel.RGB in visible) {
                add(HistogramChannel.RED to histogram.red)
                add(HistogramChannel.GREEN to histogram.green)
                add(HistogramChannel.BLUE to histogram.blue)
            }
            if (HistogramChannel.RED in visible) add(HistogramChannel.RED to histogram.red)
            if (HistogramChannel.GREEN in visible) add(HistogramChannel.GREEN to histogram.green)
            if (HistogramChannel.BLUE in visible) add(HistogramChannel.BLUE to histogram.blue)
            if (HistogramChannel.LUMINOSITY in visible) add(HistogramChannel.LUMINOSITY to histogram.luminosity)
        }.distinctBy { it.first }
        val maximum = series.maxOfOrNull { (_, bins) -> bins.maxOrNull() ?: 0.0 } ?: 0.0
        if (maximum <= 0.0) return@Canvas
        series.forEach { (channel, bins) ->
            val path = Path()
            bins.forEachIndexed { index, count ->
                val x = left + (right - left) * index / 255f
                val y = bottom - (bottom - top) * (count / maximum).toFloat().coerceIn(0f, 1f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, channel.color(), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
