package me.nettrash.geo.data.snapshot

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.data.model.InformationToken
import me.nettrash.geo.util.AppLog
import kotlin.math.abs

/**
 * Cross-process snapshot store shared by main app, widget, worker,
 * and Wear connectivity bridge. Direct port of iOS
 * `Core/SharedSnapshotStore.swift`.
 *
 * iOS uses an App Group `UserDefaults` suite for inter-process
 * exchange. Android's equivalent is a `MODE_PRIVATE` `SharedPreferences`
 * file in the same app, since all our processes (main, widget,
 * worker, future wear-bridge service) live under the same UID.
 *
 * The store has two keys:
 *  * `current`  — JSON-encoded most-recent [InformationToken]
 *  * `buffer`   — JSON-encoded `List<InformationToken>` ring buffer
 *
 * The ring buffer lets background-only writers (the Glance widget's
 * worker, the Wear connectivity bridge) hand samples to the main app
 * for backfill into the Room history. The main app drains the buffer
 * on launch and on resume — see `GeoViewModel.restoreFromSharedStorage`.
 */
object SharedSnapshotStore {

    private const val PREFS_NAME = "me.nettrash.geo.snapshot"

    private const val KEY_CURRENT = "current"
    private const val KEY_BUFFER  = "buffer"

    /** Maximum entries kept in the buffer (matches iOS). */
    const val BUFFER_CAPACITY = 12

    /** Skip writes if both pressure and timestamp delta are below
     *  this threshold — prevents the buffer from being spammed when
     *  the barometer is reporting a stable value. */
    private const val DEDUP_PRESSURE_DELTA_KPA = 0.005
    private const val DEDUP_TIME_DELTA_MS = 5_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Current snapshot ─────────────────────────────────────────

    /** Most recent snapshot, or `null` if nothing has been written. */
    fun readCurrent(context: Context): InformationToken? {
        val raw = prefs(context).getString(KEY_CURRENT, null) ?: return null
        return try {
            json.decodeFromString<InformationToken>(raw)
        } catch (t: Throwable) {
            AppLog.app.warn("Failed to decode current snapshot", t)
            null
        }
    }

    /** Persist [token] as the current snapshot AND append it to the
     *  ring buffer. Caller is responsible for triggering any widget
     *  reload they want. */
    fun write(context: Context, token: InformationToken) {
        val p = prefs(context)
        try {
            p.edit().putString(KEY_CURRENT, json.encodeToString(token)).apply()
        } catch (t: Throwable) {
            AppLog.app.warn("Failed to encode current snapshot", t)
        }
        appendToBuffer(p, token)
    }

    // ── Ring buffer (for backfill) ───────────────────────────────

    /** Buffered snapshots, oldest first. */
    fun readBuffer(context: Context): List<InformationToken> {
        val raw = prefs(context).getString(KEY_BUFFER, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<InformationToken>>(raw)
        } catch (t: Throwable) {
            AppLog.app.warn("Failed to decode buffer", t)
            emptyList()
        }
    }

    /** Replace the buffer (used when the main app drains it). */
    fun writeBuffer(context: Context, tokens: List<InformationToken>) {
        val p = prefs(context)
        if (tokens.isEmpty()) {
            p.edit().remove(KEY_BUFFER).apply()
            return
        }
        try {
            p.edit().putString(KEY_BUFFER, json.encodeToString(tokens)).apply()
        } catch (t: Throwable) {
            AppLog.app.warn("Failed to encode buffer", t)
        }
    }

    /** Drop every buffered snapshot. Called after backfill. */
    fun clearBuffer(context: Context) {
        prefs(context).edit().remove(KEY_BUFFER).apply()
    }

    private fun appendToBuffer(p: SharedPreferences, token: InformationToken) {
        val existing = try {
            p.getString(KEY_BUFFER, null)?.let {
                json.decodeFromString<List<InformationToken>>(it)
            } ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }

        // Skip near-duplicates that would otherwise spam the buffer
        // when the barometer reports the same value many times in a
        // row.
        existing.lastOrNull()?.let { last ->
            if (abs(last.barPreassure - token.barPreassure) < DEDUP_PRESSURE_DELTA_KPA &&
                abs(last.recordDate - token.recordDate) < DEDUP_TIME_DELTA_MS
            ) {
                return
            }
        }

        val updated = (existing + token).takeLast(BUFFER_CAPACITY)
        try {
            p.edit().putString(KEY_BUFFER, json.encodeToString(updated)).apply()
        } catch (t: Throwable) {
            AppLog.app.warn("Failed to append snapshot to buffer", t)
        }
    }
}
