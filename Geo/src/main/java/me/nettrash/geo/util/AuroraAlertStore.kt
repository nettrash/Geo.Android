package me.nettrash.geo.util

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the opt-in aurora alert state — whether the user asked for
 * alerts at all, and when the last one was delivered — across
 * [me.nettrash.geo.worker.SpaceWeatherWorker] ticks and process death, so
 * an aurora alert fires at most once per
 * [Geomagnetic.AURORA_ALERT_COOLDOWN_HOURS].
 *
 * Mirrors the iOS app-group `AuroraAlertsEnabled` /
 * `AuroraAlertLastNotifiedAt` keys. A separate preferences file from
 * [StormWarningStore]'s `me.nettrash.geo.storm`, because these are two
 * unrelated features that happen to share a shape: the barometric storm
 * warning is always on, and this one is off until asked for.
 *
 * Writes go through `edit(commit = true)` — synchronous `commit()`, not
 * `apply()`. For the timestamp the reason is the same as the storm store's:
 * the only caller
 * is the background worker (already off the main thread), and the
 * cooldown must be durably on disk *before* the OS can reclaim the worker
 * process — `apply()`'s async flush isn't guaranteed to land before a
 * background-process kill, which would drop the timestamp and re-fire the
 * alert on the next tick. The enabled flag is written from the UI
 * instead, where the argument is different but no weaker: process start
 * reads this flag to decide whether to schedule the worker at all, so a
 * lost write would leave the flag and the schedule disagreeing. It is one
 * boolean.
 */
class AuroraAlertStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the user has opted in to aurora alerts. Default false —
     *  a default install does no background network at all. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_ENABLED, enabled) }
    }

    /** Epoch-ms of the last delivered aurora alert, or null if none. Null
     *  rather than 0 because that is what the pure gate reads as "there is
     *  no cooldown to serve" — see [AuroraAlert.shouldNotify]. */
    fun lastNotifiedAtMs(): Long? = prefs.getLong(KEY_LAST_NOTIFIED, 0L).takeIf { it > 0L }

    fun setLastNotifiedAtMs(ms: Long) {
        prefs.edit(commit = true) { putLong(KEY_LAST_NOTIFIED, ms) }
    }

    /** Reset the cooldown so the next qualifying night alerts immediately.
     *  Called when the user opts out: someone who turns alerts back on
     *  months later should not be silenced by a timestamp from the last
     *  time they used the feature. */
    fun clear() {
        prefs.edit(commit = true) { remove(KEY_LAST_NOTIFIED) }
    }

    companion object {
        private const val PREFS_NAME = "me.nettrash.geo.aurora"
        private const val KEY_ENABLED = "alerts_enabled"
        private const val KEY_LAST_NOTIFIED = "last_notified_at_ms"
    }
}
