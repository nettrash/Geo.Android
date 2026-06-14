package me.nettrash.geo.util

import android.annotation.SuppressLint
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
 *
 * Writes use synchronous `commit()` rather than `apply()`: the only
 * caller is the background worker (already off the main thread), and the
 * cooldown must be durably on disk *before* the OS can reclaim the
 * worker process. `apply()`'s async flush isn't guaranteed to land
 * before a background-process kill, which would drop the timestamp and
 * re-fire the alert on the next tick.
 */
class StormWarningStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch-ms of the last delivered storm notification, or 0 if none. */
    fun lastNotifiedAtMs(): Long = prefs.getLong(KEY_LAST_NOTIFIED, 0L)

    @SuppressLint("ApplySharedPref") // durable, background-thread write — see class KDoc
    fun setLastNotifiedAtMs(ms: Long) {
        prefs.edit().putLong(KEY_LAST_NOTIFIED, ms).commit()
    }

    /** Reset the cooldown so the next alert fires immediately. Called when
     *  the tendency recovers to steady/rising. */
    @SuppressLint("ApplySharedPref") // durable, background-thread write — see class KDoc
    fun clear() {
        prefs.edit().remove(KEY_LAST_NOTIFIED).commit()
    }

    companion object {
        private const val PREFS_NAME = "me.nettrash.geo.storm"
        private const val KEY_LAST_NOTIFIED = "last_notified_at_ms"
    }
}
