package me.nettrash.geo.ar

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.content.FileProvider
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.nettrash.geo.R
import me.nettrash.geo.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import kotlin.coroutines.resume

/**
 * Bitmap share helper: writes a PNG to `cacheDir/shared` and hands it to the
 * system share sheet via [FileProvider]. Fully on-device — the app never uploads
 * the image.
 *
 * This used to also own the Nature tab's freeze-frame panorama capture
 * (a [PixelCopy] grab of the Filament camera surface composited with the Compose
 * marker/skyline overlay). The Nature shutter was removed, so all that's left is
 * the plain bitmap share, still used by the Summit-log share card.
 */
object PanoramaCapture {

    /**
     * Write an already-rendered [bitmap] to cacheDir/shared and hand it to the
     * system share sheet.
     */
    suspend fun shareBitmap(context: Context, bitmap: Bitmap): Boolean = try {
        val uri = withContext(Dispatchers.IO) { writePng(context, bitmap) }
        if (uri == null) {
            false
        } else {
            withContext(Dispatchers.Main) { share(context, uri) }
            true
        }
    } catch (t: Throwable) {
        AppLog.ar.warn("Bitmap share failed", t)
        false
    }

    private fun writePng(context: Context, bmp: Bitmap): Uri? = try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Prune panoramas older than an hour so cacheDir doesn't grow without
        // bound. A file a share target is still reading is always fresh, so this
        // can't delete an in-flight share.
        val cutoff = System.currentTimeMillis() - 3_600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        // Unique name per capture: a rapid re-capture must not overwrite a file
        // the previous share is still reading, and targets that cache by
        // URI/filename would otherwise show a stale image.
        val file = File(dir, "geo-panorama-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (t: Throwable) {
        AppLog.ar.warn("Failed to write panorama", t)
        null
    }

    private fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Also set clipData so the temporary read grant propagates reliably
            // to the chosen target across Android versions/OEMs (FLAG alone
            // isn't always sufficient through the chooser).
            clipData = ClipData.newUri(context.contentResolver, "Geo panorama", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.ar_share_title)))
    }
}
