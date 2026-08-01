package me.nettrash.geo.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable on-disk backing for [SpaceWeatherRepository]'s fetched Kp
 * series. Mirrors iOS `SpaceWeatherService`'s `SpaceWeatherCache.json`.
 *
 * Why this exists
 * ---------------
 * The magnetic-conditions card is the one card in the app that needs a
 * network call, and a hiker opens it precisely where there is no signal.
 * A cached series keeps the Field / Compass / GPS / Aurora rows resolved
 * for as long as one of its own bins still covers this moment — about 65
 * hours off a full payload, with [Geomagnetic.CACHED_MAX_AGE_HOURS] behind
 * it as a backstop — and after that the card still shows the half that
 * never needed the network (magnetic latitude, the Kp threshold, tonight's
 * darkness).
 *
 * Format
 * ------
 * A single JSON object (`space_weather.json`) under `context.filesDir`:
 * the fetch stamp plus the 3-hour bins, exactly as they came off SWPC.
 * Provenance is persisted as SWPC's own lowercase vocabulary rather than
 * the Kotlin enum name, so the file is byte-shaped like the iOS cache
 * (where `KpProvenance` is a `String`-backed `Codable`).
 *
 * Every field carries a default, so a blob written by an older build —
 * or by a newer one that has since gained a field — still decodes instead
 * of throwing the whole cache away. Same rule as `SharedSnapshotStore`
 * and the offline packs.
 *
 * Failure tolerance
 * -----------------
 * All I/O is best-effort: a missing, truncated or corrupt file reads back
 * as "no cache", and write failures are logged and swallowed. Losing the
 * cache costs one network round-trip, never correctness.
 */
class SpaceWeatherStore(private val directory: File) {

    /**
     * Production entry point: the app's own private files directory. The
     * directory is the ONLY seam this class has — the JVM suite drives
     * the real [read] and [write] paths against a scratch folder instead
     * of a copy of them, because the disk type is deliberately NOT
     * [KpSeries] and the mapping between the two is the part that can
     * silently drop a field.
     */
    constructor(context: Context) : this(context.filesDir)

    /** One persisted 3-hour Kp bin. [timeMs] is the START of the bin, UTC. */
    @Serializable
    data class Entry(
        val timeMs: Long = 0L,
        val kp: Double = 0.0,
        val provenance: String = PROVENANCE_PREDICTED
    )

    /** The whole cached payload: our own fetch stamp plus the bins. The
     *  fetch stamp is what freshness is measured from — never SWPC's
     *  `last-modified`, which is rewritten every minute regardless of
     *  content age. */
    @Serializable
    data class Cached(
        val fetchedAtMs: Long = 0L,
        val points: List<Entry> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val file: File
        get() = File(directory, FILE_NAME)

    /**
     * Load the cached series, or null on a cold cache, an unreadable file
     * or an empty payload. Points come back sorted ascending, because the
     * rest of the feature is allowed to assume that and a hand-edited
     * file is not.
     */
    suspend fun read(): KpSeries? = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext null
        try {
            val cached = json.decodeFromString<Cached>(f.readText())
            if (cached.points.isEmpty()) return@withContext null
            KpSeries(
                fetchedAtMs = cached.fetchedAtMs,
                points = cached.points
                    .map { KpPoint(it.timeMs, it.kp, provenanceFromString(it.provenance)) }
                    .sortedBy { it.timeMs }
            )
        } catch (t: Throwable) {
            AppLog.spaceWeather.warn("Kp cache load failed", t)
            null
        }
    }

    /**
     * Persist [series], replacing any prior file. Written via a temp file
     * + atomic rename so a crash mid-write can never leave a half-written
     * payload that would then read back as "no cache". Best-effort.
     */
    suspend fun write(series: KpSeries) = withContext(Dispatchers.IO) {
        try {
            val cached = Cached(
                fetchedAtMs = series.fetchedAtMs,
                points = series.points.map { Entry(it.timeMs, it.kp, provenanceToString(it.provenance)) }
            )
            val tmp = File(directory, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(cached))
            if (!tmp.renameTo(file)) {
                // Fall back to a direct overwrite if rename is refused.
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            AppLog.spaceWeather.warn("Kp cache save failed", t)
        }
    }

    private fun provenanceToString(provenance: KpProvenance): String = when (provenance) {
        KpProvenance.OBSERVED -> PROVENANCE_OBSERVED
        KpProvenance.ESTIMATED -> PROVENANCE_ESTIMATED
        KpProvenance.PREDICTED -> PROVENANCE_PREDICTED
    }

    /** An unrecognised tag reads back as `PREDICTED`, which the card
     *  captions "forecast" — the reading that under-claims rather than
     *  passing off an unknown row as an observation. */
    private fun provenanceFromString(raw: String): KpProvenance = when (raw) {
        PROVENANCE_OBSERVED -> KpProvenance.OBSERVED
        PROVENANCE_ESTIMATED -> KpProvenance.ESTIMATED
        else -> KpProvenance.PREDICTED
    }

    private companion object {
        const val FILE_NAME = "space_weather.json"

        /** SWPC's own spellings, shared with the iOS cache file. */
        const val PROVENANCE_OBSERVED = "observed"
        const val PROVENANCE_ESTIMATED = "estimated"
        const val PROVENANCE_PREDICTED = "predicted"
    }
}
