package me.nettrash.geo.util

import android.content.Context

/**
 * Persists the storm-warning notification cooldown across
 * [me.nettrash.geo.worker.BarometerRefreshWorker] ticks and process
 * death, so a falling-pressure alert fires at most once per
 * [me.nettrash.geo.sensor.StormWarning.NOTIFICATION_COOLDOWN_HOURS] and
 * resets when the weather recovers.
 *
 * Mirrors the iOS app-group `StormWarningLastNotifiedAt` key. Backed by
 * a plain `SharedPreferences` so the worker can read/write it
 * synchronously without a coroutine hop.
 */
class StormWarningStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch-ms of the last delivered storm notification, or 0 if none. */
    fun lastNotifiedAtMs(): Long = prefs.getLong(KEY_LAST_NOTIFIED, 0L)

    fun setLastNotifiedAtMs(ms: Long) {
        prefs.edit().putLong(KEY_LAST_NOTIFIED, ms).apply()
    }

    /** Reset the cooldown so the next alert fires immediately. Called when
     *  the tendency recovers to steady/rising. */
    fun clear() {
        prefs.edit().remove(KEY_LAST_NOTIFIED).apply()
    }

    companion object {
        private const val PREFS_NAME = "me.nettrash.geo.storm"
        private const val KEY_LAST_NOTIFIED = "last_notified_at_ms"
    }
}
