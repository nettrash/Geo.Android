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
import me.nettrash.geo.util.AuroraVisibility
import me.nettrash.geo.util.Geomagnetic
import me.nettrash.geo.util.MagneticConditions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Delivers the opt-in aurora alert via [NotificationManagerCompat]. iOS
 * posts the equivalent local `UNUserNotification` from its
 * `BGAppRefreshTask` handler.
 *
 * Structurally a clone of [StormNotifier], with one deliberate difference:
 * its own channel. A hiker who wants the falling-pressure warning must be
 * able to silence aurora pings without losing it — they are different
 * promises, and one of them arrives at midnight. Hence a second channel at
 * `IMPORTANCE_DEFAULT`: an aurora is worth knowing about, but nothing here
 * is a safety alert.
 *
 * The channel is created lazily (and idempotently) so it exists before the
 * first notification regardless of whether the app's process warmed it up.
 * Delivery is gated on the Android 13+ `POST_NOTIFICATIONS` runtime
 * permission: a denial degrades silently (no crash, no alert).
 *
 * Nothing here decides anything. The decision is the pure
 * [me.nettrash.geo.util.AuroraAlert.shouldNotify]; this file only renders
 * a state it has already been told to announce.
 */
object AuroraNotifier {

    const val CHANNEL_ID = "space_weather"
    private const val NOTIFICATION_ID = 4002

    /** Create the aurora-alerts channel if it doesn't exist yet. No O
     *  version guard, unlike [StormNotifier]: minSdk is 28, so channels are
     *  unconditional here and lint flags the check as dead. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aurora alerts",
            // Not IMPORTANCE_HIGH, unlike the storm warning: the aurora is
            // a reason to go outside, not a reason to turn back.
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Nights when the auroral oval could reach your location. Off by default."
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Post a single aurora alert for an already-qualified [conditions].
     *
     * The body names the Kp bin it came from, so nobody reads it as a
     * statement about the sky right now, and it says "forecast" even when
     * the bin is tagged observed — by the time anyone is standing outside
     * it is a forecast, and that is the direction that under-claims.
     *
     * Two bodies, split on whether the oval reaches the observer at all:
     * at margin >= 0 they are inside it and look toward the pole, below
     * that the display is over the horizon and they need an open view of
     * somewhere they cannot stand. No brightness adjective appears in
     * either — the model has no brightness term.
     */
    fun postAuroraAlert(context: Context, conditions: MagneticConditions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.spaceWeather.info("POST_NOTIFICATIONS not granted — aurora alert suppressed")
            return
        }

        val body = body(context, conditions) ?: return

        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aurora)
            .setContentTitle("Aurora possible tonight")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (se: SecurityException) {
            // Belt-and-braces: permission could be revoked between the
            // check above and delivery. Never crash the worker over it.
            AppLog.spaceWeather.warn("Aurora notification denied", se)
        }
    }

    /**
     * Render the body, or null if any piece is missing. The alert gate
     * already guarantees all of them, so null here means somebody called
     * this without asking the gate first — say nothing rather than post a
     * sentence with a hole in it.
     */
    private fun body(context: Context, conditions: MagneticConditions): String? {
        val kp = conditions.kpNow ?: return null
        val binStartMs = conditions.binStartMs ?: return null
        val darkStartMs = conditions.aurora.windowStartMs ?: return null

        // Kp on its own thirds ("6", "5+", "9-"), never a decimal: this is
        // the observatories' own notation and the card's own rule.
        val kpLabel = Geomagnetic.kpThirdsLabel(kp)
        val span = binSpanUtc(binStartMs)
        // Device 12/24-hour preference and device zone — the one number in
        // the body the reader has to act on.
        val darkStart = android.text.format.DateFormat.getTimeFormat(context)
            .format(Date(darkStartMs))
        val northern = conditions.aurora.isNorthernHemisphere
        val direction = if (northern) "north" else "south"
        val horizonSide = if (northern) "northern" else "southern"

        return if (conditions.aurora.visibility == AuroraVisibility.HORIZON_GLOW) {
            String.format(
                Locale.US,
                "Kp %s forecast for %s. Possible glow low on the %s horizon from %s. " +
                    "You'll want a clear, open view %s.",
                kpLabel, span, horizonSide, darkStart, direction
            )
        } else {
            String.format(
                Locale.US,
                "Kp %s forecast for %s. From where you are the auroral oval reaches " +
                    "overhead — look %s from %s.",
                kpLabel, span, direction, darkStart
            )
        }
    }

    /** "22:00–01:00 UTC" — the Kp bin's own 3-hour span, always in UTC and
     *  always labelled, because that is how SWPC publishes it and the
     *  reader's local evening is a different thing entirely. */
    private fun binSpanUtc(binStartMs: Long): String {
        val format = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val binMs = (Geomagnetic.KP_BIN_HOURS * 3_600_000.0).toLong()
        return "${format.format(Date(binStartMs))}–" +
            "${format.format(Date(binStartMs + binMs))} UTC"
    }
}
