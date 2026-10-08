package app.croptop.feature.editor.dialogs

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.LayerThumb
import app.croptop.core.engine.api.PatternInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared, thumbnail-backed picker used by Pattern Fill, Pattern Overlay, and the library. */
@Composable
fun PatternPicker(
    patterns: List<PatternInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    patternThumbnail: (String, Int) -> LayerThumb?,
    onDelete: ((String) -> Unit)? = null,
) {
    if (patterns.isEmpty()) {
        AeText("No saved patterns. Use Edit → Define Pattern to add one.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 144.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(patterns, key = { it.id }) { pattern ->
            val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pattern.id) {
                value = withContext(Dispatchers.IO) {
                    runCatching { patternThumbnail(pattern.id, 96)?.toImageBitmap() }.getOrNull()
                }
            }
            val isSelected = selectedId == pattern.id
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(3.dp),
                    color = if (isSelected) Ae.colors.surface3 else Ae.colors.surface2,
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) Ae.colors.accent else Ae.colors.lineHairline),
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1.6f).semantics {
                                contentDescription = "${pattern.name} preview on transparency grid"
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            PatternTransparencyGrid(Modifier.fillMaxWidth().aspectRatio(1.6f))
                            bitmapState.value?.let { bitmap ->
                                Image(
                                    bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit,
                                )
                            } ?: AeText("Preview unavailable", color = Ae.colors.textSecondary)
                        }
                        AeButton(
                            "${if (isSelected) "✓ " else ""}${pattern.name}",
                            { onSelect(pattern.id) },
                            modifier = Modifier.fillMaxWidth().semantics {
                                selected = isSelected
                                role = Role.RadioButton
                            },
                        )
                        AeText(
                            "${pattern.width} × ${pattern.height}",
                            color = Ae.colors.textSecondary,
                            maxLines = 1,
                        )
                        onDelete?.let { delete ->
                            AeButton("Delete ${pattern.name}", { delete(pattern.id) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternTransparencyGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = 8.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var column = 0
            while (x < size.width) {
                drawRect(
                    color = if ((column + row) % 2 == 0) Color(0xFFBDBDBD) else Color(0xFF8E8E8E),
                    topLeft = Offset(x, y),
                    size = Size(cell, cell),
                )
                x += cell
                column++
            }
            y += cell
            row++
        }
    }
}

private fun LayerThumb.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val pixels = IntArray(width * height) { i ->
        val p = i * 4
        ((rgba[p + 3].toInt() and 255) shl 24) or
            ((rgba[p].toInt() and 255) shl 16) or
            ((rgba[p + 1].toInt() and 255) shl 8) or
            (rgba[p + 2].toInt() and 255)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
