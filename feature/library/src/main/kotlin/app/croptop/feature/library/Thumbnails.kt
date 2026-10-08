package app.croptop.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.croptop.core.data.thumbnails.ThumbnailLoader

@Composable
fun rememberThumbnail(uri: String, px: Int): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(uri, px) { mutableStateOf(ThumbnailLoader.cached(uri, px)?.asImageBitmap()) }
    LaunchedEffect(uri, px) { if (image == null) image = ThumbnailLoader.load(context, uri, px)?.asImageBitmap() }
    return image
}
