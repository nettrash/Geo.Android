package me.nettrash.geo.wear

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-device persistence for the Wear app's transient state.
 *
 * Mirrors iOS `GeoWatchAppDelegate.restoreFromSharedStorage()`:
 * Wear OS kills the watch process routinely, and without persistence
 * the calibration token resets to `null` (so the barometer falls back
 * to the weather-biased standard-atmosphere formula) and the altitude
 * sparkline starts empty on every relaunch. We keep the last
 * phone-pushed calibration token and a small altitude-history ring so
 * the very first tick after a relaunch is already calibrated and the
 * graph is non-empty.
 *
 * Uses a `MODE_PRIVATE` `SharedPreferences` file — the same framework
 * primitive the phone module's `SharedSnapshotStore` uses — so no
 * extra dependency is required. Reads/writes are small and infrequent
 * (one calibration token + a <=20-entry double ring), so the
 * synchronous `SharedPreferences` API is fine here.
 *
 * The persisted token JSON is byte-compatible with the on-wire
 * `/geo/snapshot` payload (same [WearInformationToken] shape,
 * `encodeDefaults = true`) so it round-trips with the phone/iOS.
 */
object WearPersistStore {

    private const val PREFS_NAME = "me.nettrash.geo.wear.persist"

    /** JSON-encoded last calibration [WearInformationToken]. */
    private const val KEY_TOKEN = "calibToken"

    /** JSON-encoded [HistoryRing] of recent altitudes. */
    private const val KEY_HISTORY = "altitudeHistory"

    /** Cap on the persisted altitude ring (matches the UI sparkline). */
    const val HISTORY_CAPACITY = 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Persisted altitude history. Stamped with [recordDate] (the
     * token timestamp at the time the ring was last written) so a
     * reader can judge staleness, matching the iOS restore path.
     */
    @Serializable
    data class HistoryRing(
        val recordDate: Long = 0L,
        val altitudes: List<Double> = emptyList()
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Calibration token ────────────────────────────────────────

    /** Last persisted calibration token, or `null` if none / undecodable. */
    fun readToken(context: Context): WearInformationToken? {
        val raw = prefs(context).getString(KEY_TOKEN, null) ?: return null
        return runCatching { json.decodeFromString<WearInformationToken>(raw) }.getOrNull()
    }

    /** Persist [token] as the last-known calibration reference. */
    fun writeToken(context: Context, token: WearInformationToken) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_TOKEN, json.encodeToString(token))
                .apply()
        }
    }

    // ── Altitude history ring ────────────────────────────────────

    /** Restore the persisted altitude ring (empty if none / undecodable). */
    fun readHistory(context: Context): List<Double> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<HistoryRing>(raw).altitudes }
            .getOrDefault(emptyList())
    }

    /**
     * Persist [altitudes] (already capped by the caller to
     * [HISTORY_CAPACITY]). [recordDate] stamps the write so staleness
     * can be judged on restore.
     */
    fun writeHistory(context: Context, altitudes: List<Double>, recordDate: Long) {
        runCatching {
            val ring = HistoryRing(
                recordDate = recordDate,
                altitudes = altitudes.takeLast(HISTORY_CAPACITY)
            )
            prefs(context).edit()
                .putString(KEY_HISTORY, json.encodeToString(ring))
                .apply()
        }
    }
}
