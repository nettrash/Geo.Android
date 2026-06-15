package me.nettrash.geo.ar

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
 * Freeze-frame share for the AR Nature view.
 *
 * The camera + 3D scene are rendered by Filament directly into the
 * [ARSceneView]'s Surface, so a normal view→Canvas / Compose capture reads
 * them as black — the camera layer MUST be grabbed with [PixelCopy]. The
 * Compose overlay (markers + skyline) is captured separately by the caller
 * (`GraphicsLayer.toImageBitmap()` on the main thread) and handed in as
 * [overlay]; this object composites the two, draws a small branding footer,
 * writes a PNG to `cacheDir/shared`, and hands it to the system share sheet
 * via [FileProvider]. Fully on-device — the app never uploads the image.
 */
object PanoramaCapture {

    /**
     * @return true if the image was rendered and a chooser was launched.
     * [overlay] must be produced on the main thread before this is called.
     */
    suspend fun captureAndShare(
        context: Context,
        arView: ARSceneView,
        overlay: Bitmap,
        markerCount: Int
    ): Boolean = try {
        val camera = pixelCopy(arView)
        if (camera == null) {
            false
        } else {
            val composed = withContext(Dispatchers.Default) {
                composite(context, camera, overlay, markerCount)
            }
            camera.recycle()
            val uri = withContext(Dispatchers.IO) { writePng(context, composed) }
            composed.recycle()
            if (uri == null) {
                false
            } else {
                withContext(Dispatchers.Main) { share(context, uri) }
                true
            }
        }
    } catch (t: Throwable) {
        // Never let a capture/render failure escape to the uncaught handler.
        AppLog.ar.warn("Panorama capture failed", t)
        false
    }

    /** Read the live SurfaceView pixels into a Bitmap. Returns null if the
     *  surface isn't valid / the copy failed (e.g. AR paused). */
    private suspend fun pixelCopy(view: ARSceneView): Bitmap? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    view, bmp,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            cont.resume(bmp)
                        } else {
                            AppLog.ar.warn("PixelCopy returned $result")
                            bmp.recycle()
                            cont.resume(null)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (t: Throwable) {
                AppLog.ar.warn("PixelCopy request failed", t)
                bmp.recycle()
                cont.resume(null)
            }
        }
    }

    private fun composite(context: Context, camera: Bitmap, overlay: Bitmap, markerCount: Int): Bitmap {
        val result = camera.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        // GraphicsLayer.toImageBitmap() returns a HARDWARE bitmap on API 28+,
        // which a software Canvas refuses to draw ("Software rendering doesn't
        // support hardware bitmaps"). Read it back into a software ARGB_8888
        // copy first. Leave the original (Compose-owned) overlay untouched.
        val softOverlay = if (overlay.config == Bitmap.Config.HARDWARE) {
            overlay.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            overlay
        }
        // Overlay is full-screen Compose px; scale to the camera px size in the
        // (rare) event they differ so markers stay aligned with the frame.
        canvas.drawBitmap(
            softOverlay,
            Rect(0, 0, softOverlay.width, softOverlay.height),
            Rect(0, 0, result.width, result.height),
            null
        )
        if (softOverlay !== overlay) softOverlay.recycle()
        drawFooter(context, canvas, result.width, result.height, markerCount)
        return result
    }

    private fun drawFooter(context: Context, canvas: Canvas, w: Int, h: Int, markerCount: Int) {
        val d = context.resources.displayMetrics.density
        val bandH = 54f * d
        canvas.drawRect(0f, h - bandH, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(115, 0, 0, 0) })

        val pad = 18f * d
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15f * d; isFakeBoldText = true
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255); textSize = 12f * d
        }
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 152, 0); textSize = 15f * d; isFakeBoldText = true
        }
        val baseline = h - bandH / 2f + title.textSize / 3f
        // Orange ▲ + "Geo" to mirror the iOS mountain glyph + wordmark.
        canvas.drawText("▲", pad, baseline, glyph)
        val titleX = pad + glyph.measureText("▲ ")
        canvas.drawText("Geo", titleX, baseline, title)

        if (markerCount > 0) {
            val x = titleX + title.measureText("Geo") + 10f * d
            canvas.drawText(context.getString(R.string.ar_share_marked, markerCount), x, baseline, sub)
        }

        val now = Date()
        val stamp = "${android.text.format.DateFormat.getDateFormat(context).format(now)} " +
            android.text.format.DateFormat.getTimeFormat(context).format(now)
        canvas.drawText(stamp, w - pad - sub.measureText(stamp), baseline, sub)
    }

    private fun writePng(context: Context, bmp: Bitmap): Uri? = try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "geo-panorama.png")
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.ar_share_title)))
    }
}
