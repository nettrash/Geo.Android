package me.nettrash.geo.util

import android.annotation.SuppressLint
import android.content.Context

/**
 * Persists the in-progress trip-recording start time so a recording
 * survives process death / app restart (mirrors the iOS
 * `TripRecordingStartedAt` UserDefaults key).
 *
 * Writes use synchronous `commit()` rather than `apply()`: these are rare,
 * deliberate state transitions (start / stop / cancel) that MUST be durable
 * before the process can die, or the next launch recovers a stale
 * "recording" state. The cost is a tiny, infrequent main-thread write.
 */
class TripRecordingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch-ms the current recording started, or `null` if not recording. */
    fun startedAtMs(): Long? =
        if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, 0L) else null

    @SuppressLint("ApplySharedPref") // durable recording state — see class KDoc
    fun setStartedAtMs(ms: Long) {
        prefs.edit().putLong(KEY_STARTED_AT, ms).commit()
    }

    @SuppressLint("ApplySharedPref") // durable recording state — see class KDoc
    fun clear() {
        prefs.edit().remove(KEY_STARTED_AT).commit()
    }

    companion object {
        private const val PREFS_NAME = "me.nettrash.geo.trip"
        private const val KEY_STARTED_AT = "started_at_ms"
    }
}
