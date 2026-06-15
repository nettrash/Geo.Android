package me.nettrash.geo.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import me.nettrash.geo.data.db.SummitLog
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a shareable "summit card" Bitmap for a [SummitLog] — peak name,
 * elevation, logged date, optional note + "Geo" branding over a gradient.
 * Pure Canvas (no off-screen Compose). Embeds only PUBLIC peak data and the
 * user's own measured altitude — never the user's raw GPS position.
 */
object SummitShareCard {

    fun render(context: Context, log: SummitLog): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (340 * d).toInt()
        val h = (440 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f

        // Gradient background.
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.rgb(15, 25, 46), Color.rgb(26, 41, 31), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val orange = Color.rgb(255, 152, 0)
        var y = 64f * d

        // ▲ glyph
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = orange; textSize = 44f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        canvas.drawText("▲", cx, y, glyph)
        y += 26f * d

        // Set label (uppercase).
        val setLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = orange; textSize = 13f * d; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(setLabelText(log.peakSet).uppercase(Locale.ROOT), cx, y, setLabel)
        y += 36f * d

        // Peak name (centred, up to 2 lines, ellipsised).
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f * d
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        y = drawCentredBlock(canvas, log.peakName.ifEmpty { "Summit" }, name, w, d, y, maxLines = 2)
        y += 8f * d

        // Elevation.
        if (log.peakAltitude > 0) {
            val alt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 255, 255, 255); textSize = 20f * d; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("▲ ${log.peakAltitude} m", cx, y, alt)
            y += 28f * d
        }

        // Logged date.
        val dateP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255); textSize = 14f * d; textAlign = Paint.Align.CENTER
        }
        val stamp = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(log.loggedDate))
        canvas.drawText("Logged $stamp", cx, y, dateP)
        y += 26f * d

        // Note (italic, centred, up to 3 lines).
        val note = log.note?.trim().orEmpty()
        if (note.isNotEmpty()) {
            val noteP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 255, 255); textSize = 15f * d
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            }
            y = drawCentredBlock(canvas, "“$note”", noteP, w, d, y, maxLines = 3)
        }

        // Footer wordmark.
        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        canvas.drawText("▲ Geo", cx, h - 28f * d, foot)
        return bmp
    }

    /** Draw centred, wrapped, ellipsised text; returns the new vertical cursor. */
    private fun drawCentredBlock(
        canvas: Canvas, text: String, paint: TextPaint, w: Int, d: Float, y: Float, maxLines: Int
    ): Float {
        val width = (w - 48 * d).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(24f * d, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }

    private fun setLabelText(set: String): String = when (set) {
        "sevenPeaks" -> "Seven Summits"
        "snowLeopardOfRussia" -> "Snow Leopard"
        "highest" -> "Highest peaks"
        else -> "Peak"
    }
}
