package me.nettrash.geo.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.nettrash.geo.util.AppLog

/**
 * Loads an image from `src/main/assets/$path` and renders it as an
 * Image. Decode runs on `Dispatchers.IO` so the UI thread isn't
 * blocked.
 *
 * Renders nothing while the bitmap is loading; if the asset is
 * missing or unreadable, also renders nothing (the caller decides
 * whether to show a fallback).
 *
 * Bitmap cache is not implemented because the call site (mountain
 * detail sheet) only opens one image at a time and Glide / Coil
 * would be overkill for the bundled-asset case.
 */
@Composable
fun AssetImage(
    path: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decode(context, path) }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}

private fun decode(context: Context, path: String): ImageBitmap? = try {
    context.assets.open(path).use { stream ->
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    }
} catch (t: Throwable) {
    AppLog.app.warn("AssetImage decode failed for $path", t)
    null
}
