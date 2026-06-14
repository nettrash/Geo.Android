package me.nettrash.geo.util

import android.content.Context

/**
 * Persists the in-progress trip-recording start time so a recording
 * survives process death / app restart (mirrors the iOS
 * `TripRecordingStartedAt` UserDefaults key). Backed by a plain
 * `SharedPreferences` for synchronous read/write from the ViewModel.
 */
class TripRecordingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch-ms the current recording started, or `null` if not recording. */
    fun startedAtMs(): Long? =
        if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, 0L) else null

    fun setStartedAtMs(ms: Long) {
        prefs.edit().putLong(KEY_STARTED_AT, ms).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_STARTED_AT).apply()
    }

    companion object {
        private const val PREFS_NAME = "me.nettrash.geo.trip"
        private const val KEY_STARTED_AT = "started_at_ms"
    }
}
