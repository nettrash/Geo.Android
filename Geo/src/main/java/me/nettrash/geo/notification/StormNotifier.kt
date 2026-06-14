package me.nettrash.geo.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.nettrash.geo.R
import me.nettrash.geo.util.AppLog
import java.util.Locale

/**
 * Delivers the pressure-trend storm warning (M5a) via
 * [NotificationManagerCompat]. iOS posts the equivalent local
 * `UNUserNotification` from its BGTask handler.
 *
 * The channel is created lazily (and idempotently) so it exists before
 * the first notification regardless of whether the app's process warmed
 * it up. Delivery is gated on the Android 13+ `POST_NOTIFICATIONS`
 * runtime permission: a denial degrades silently (no crash, no alert).
 */
object StormNotifier {

    const val CHANNEL_ID = "weather_alerts"
    private const val NOTIFICATION_ID = 4001

    /** Create the weather-alerts channel on O+ if it doesn't exist yet. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Weather alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Falling-pressure storm warnings derived from your barometric history."
        }
        manager.createNotificationChannel(channel)
    }

    /** Post a single storm-warning notification. [dropHpa] is the
     *  de-trended pressure fall over the last 3 hours (positive hPa). */
    fun postStormWarning(context: Context, dropHpa: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.background.info("POST_NOTIFICATIONS not granted — storm warning suppressed")
            return
        }

        ensureChannel(context)

        val body = String.format(
            Locale.US,
            "Barometric pressure has dropped %.1f hPa in the last 3 hours — weather may be deteriorating.",
            dropHpa
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_storm)
            .setContentTitle("Pressure falling fast")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (se: SecurityException) {
            // Belt-and-braces: permission could be revoked between the
            // check above and delivery. Never crash the worker over it.
            AppLog.background.warn("Storm notification denied", se)
        }
    }
}
