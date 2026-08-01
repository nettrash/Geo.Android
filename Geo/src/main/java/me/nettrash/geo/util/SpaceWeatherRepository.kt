package me.nettrash.geo.util

import android.content.Context
import android.location.Location
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.nettrash.geo.ar.TerrainElevationService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Fetches the NOAA SWPC planetary K index and resolves it, together with
 * the user's position, into the [MagneticConditions] the Info tab's
 * magnetic card renders. Mirrors iOS `SpaceWeatherService`.
 *
 * Why this exists
 * ---------------
 * Kp is a PLANETARY index — one global number, produced from a network of
 * 13 ground observatories. Everything that turns it into a statement about
 * *this* summit (magnetic latitude, the oval's edge, which way to look,
 * tonight's darkness, the compass band, the GNSS advisory) is pure
 * arithmetic in [Geomagnetic] and runs on-device. This class is only the
 * I/O half: one fetch, one cache, one derived flow.
 *
 * Privacy
 * -------
 * The request is a CONSTANT URL with no query string. It is byte-identical
 * for every user of the app on Earth: no coordinates, not even quantised
 * ones, no identifier, no parameters of any kind. That is a strictly
 * smaller footprint than the two hosts the app already talks to
 * ([TerrainElevationService] and [QnhRepository] both carry the user's
 * quantised position in the URL). Nothing about the user leaves the phone
 * here, and the position never leaves this process.
 *
 * Network policy
 * --------------
 * At most one fetch per [Geomagnetic.FETCH_MIN_INTERVAL_HOURS], matching
 * the length of a Kp bin — a second call inside the same bin cannot return
 * a new number. Conditional GETs are deliberately NOT used: SWPC rewrites
 * its whole tree every minute, so `last-modified` is always ~40 s old
 * regardless of content age and an `If-Modified-Since` would never hit.
 * Freshness comes from the payload's own `time_tag` and our fetch stamp.
 *
 * Failure tolerance
 * -----------------
 * A failed, malformed or empty fetch NEVER overwrites a good cache, and
 * never throws to the caller — the card degrades one tier and keeps going.
 */
@Singleton
class SpaceWeatherRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** Last successfully parsed series, freshly fetched or restored from
     *  disk. */
    @Volatile
    private var series: KpSeries? = null

    /**
     * The bundled AACGM-v2 correction grid, decoded once. Null means the
     * asset is missing or the wrong size, which suppresses the aurora and
     * magnetic-latitude rows outright — the raw dipole is 5.63° too far
     * poleward at London and must never reach the UI (honesty contract
     * 12). The `verifyMlatGrid` build gate exists so this stays
     * theoretical.
     */
    @Volatile
    private var grid: MLatDeltaGrid? = null

    /** Wall-clock time of the last fetch that produced a usable series.
     *  The throttle is on SUCCESSES, so a flaky network does not lock us
     *  out for three hours. Written under [fetchLock], read outside it. */
    @Volatile
    private var lastFetchAtMs: Long = 0L

    @Volatile
    private var lastLatitude: Double? = null

    @Volatile
    private var lastLongitude: Double? = null

    /** Everything the magnetic card renders, re-resolved whenever the
     *  series, the position or the clock we resolve against changes. The
     *  initial value is the all-null "we know nothing yet" state, which
     *  the card renders as its offline tier rather than as a spinner. */
    private val _conditions = MutableStateFlow(
        Geomagnetic.conditions(
            series = null,
            latitude = null,
            longitude = null,
            grid = null,
            nowMs = System.currentTimeMillis(),
            timeZone = TimeZone.getDefault()
        )
    )
    val conditions: StateFlow<MagneticConditions> = _conditions.asStateFlow()

    /** True only while a fetch is actually in flight. The card shows one
     *  `Checking…` line off this, and NEVER blocks on it. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchLock = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(minIntervalMs = 1000))
        .build()

    private val store = SpaceWeatherStore(context)

    init {
        // Grid first, then cache: a restored series with no grid still
        // resolves the Field / Compass / GPS rows, and the aurora rows stay
        // suppressed rather than briefly showing a dipole answer.
        scope.launch {
            grid = loadGrid()
            store.read()?.let { series = it }
            recompute()
        }
    }

    /**
     * Refresh unless we already fetched inside this Kp bin. Called from the
     * Info tab's ON_RESUME. Cheap and idempotent: the common case never
     * takes the lock.
     */
    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastFetchAtMs < MIN_INTERVAL_MS) {
            // Still inside the bin, so we send nothing — but the clock has
            // moved, and freshness, the darkness window and the bin caption
            // all age with it. Resolve again anyway.
            scope.launch { recompute() }
            return
        }
        scope.launch {
            refreshLocked()
            recompute()
        }
    }

    /**
     * Refresh and resolve the card for an EXPLICIT position, suspending
     * until both are done. This is the background half of [refreshIfStale],
     * used by the opt-in [me.nettrash.geo.worker.SpaceWeatherWorker]:
     * `refreshIfStale` launches on this repository's own scope and returns
     * immediately, which would leave WorkManager free to tear the process
     * down mid-flight. A worker has to be able to await its own work.
     *
     * The position is a parameter rather than a call to [updatePosition]
     * because a worker runs with the sensors stopped — its fix comes from
     * the last persisted snapshot, and writing that stale position into
     * the live card's state would be a visible regression the moment the
     * user opened the app.
     */
    suspend fun refreshAndResolve(
        latitude: Double,
        longitude: Double,
        nowMs: Long
    ): MagneticConditions {
        // A cold background process has run neither of these yet; in a warm
        // one they are both no-ops. Cache before fetch, so a fetch that
        // fails still resolves against whatever is on disk.
        if (grid == null) grid = loadGrid()
        if (series == null) store.read()?.let { series = it }
        refreshLocked()
        return Geomagnetic.conditions(
            series = series,
            latitude = latitude,
            longitude = longitude,
            grid = grid,
            nowMs = nowMs,
            timeZone = TimeZone.getDefault()
        )
    }

    /** The one fetch path, shared by the foreground and background callers
     *  so the 3-hour throttle cannot be honoured by one and not the other.
     *  Never throws; a failure leaves everything exactly as it was. */
    private suspend fun refreshLocked() = fetchLock.withLock {
        // Re-check under the lock so two near-simultaneous callers (a tab
        // switch plus an app foreground, or a resume racing a worker tick)
        // don't fire two requests.
        val now = System.currentTimeMillis()
        if (now - lastFetchAtMs < MIN_INTERVAL_MS) return@withLock

        _isRefreshing.value = true
        try {
            val fetched = fetch(now)
            if (fetched != null && fetched.points.isNotEmpty()) {
                series = fetched
                lastFetchAtMs = now
                store.write(fetched)
                AppLog.spaceWeather.info("Kp series refreshed: ${fetched.points.size} bins")
            }
            // A null or empty result deliberately changes nothing: the
            // cache we already hold is still the best answer.
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * Feed the current fix in. Ignores a move smaller than
     * [POSITION_EPSILON_DEG] — magnetic latitude changes by 0.1° over
     * ~11 km, and re-resolving on every GPS sample would re-run the
     * darkness search (a day-by-day solar scan through a polar summer)
     * once a second for a readout that cannot visibly change.
     */
    fun updatePosition(location: Location?) {
        val lat = location?.latitude
        val lon = location?.longitude
        if (lat == null || lon == null) {
            if (lastLatitude == null && lastLongitude == null) return
            lastLatitude = null
            lastLongitude = null
            scope.launch { recompute() }
            return
        }
        val knownLat = lastLatitude
        val knownLon = lastLongitude
        if (knownLat != null && knownLon != null &&
            abs(lat - knownLat) < POSITION_EPSILON_DEG &&
            abs(lon - knownLon) < POSITION_EPSILON_DEG
        ) return

        lastLatitude = lat
        lastLongitude = lon
        scope.launch { recompute() }
    }

    /** Resolve the card from whatever we currently hold. Off the main
     *  thread: the darkness window is a handful of solar evaluations, and
     *  through a polar summer a day-by-day scan on top. */
    private suspend fun recompute() = withContext(Dispatchers.Default) {
        _conditions.value = Geomagnetic.conditions(
            series = series,
            latitude = lastLatitude,
            longitude = lastLongitude,
            grid = grid,
            nowMs = System.currentTimeMillis(),
            // The device zone, read here and injected — the core never
            // reads a clock or a zone of its own, so the darkness window is
            // deterministic under test.
            timeZone = TimeZone.getDefault()
        )
    }

    /** Decode the bundled grid asset. A missing or wrong-sized asset is a
     *  build error, not a runtime condition, so it is logged loudly. */
    private suspend fun loadGrid(): MLatDeltaGrid? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.assets.open(GRID_ASSET).use { it.readBytes() }
            MLatDeltaGrid.decode(bytes).also {
                if (it == null) {
                    AppLog.spaceWeather.error(
                        "Correction grid is ${bytes.size} bytes, expected " +
                            "${Geomagnetic.MLAT_GRID_BYTE_COUNT} — aurora rows suppressed"
                    )
                }
            }
        } catch (t: Throwable) {
            AppLog.spaceWeather.error("Correction grid asset unreadable", t)
            null
        }
    }

    private suspend fun fetch(nowMs: Long): KpSeries? = withContext(Dispatchers.IO) {
        try {
            client.newCall(forecastRequest()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.spaceWeather.warn("Kp HTTP ${resp.code}")
                    return@withContext null
                }
                parseKpForecast(resp.body.string(), nowMs)
            }
        } catch (t: Throwable) {
            AppLog.spaceWeather.warn("Kp fetch failed", t)
            null
        }
    }

    companion object {

        /**
         * The entire network surface of this feature. A constant, never
         * assembled with a query string, so there is nowhere for a
         * coordinate to be appended later by accident.
         */
        const val FORECAST_URL =
            "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"

        /**
         * The ONE request this feature ever sends, built here rather than
         * inline in [fetch] so the privacy claim is a test and not a
         * reading of the source: a plain GET of [FORECAST_URL] with no
         * query string and NO conditional headers.
         *
         * `If-Modified-Since` / `If-None-Match` are deliberately absent.
         * SWPC rewrites its whole tree every minute, so `last-modified` is
         * always seconds old regardless of when the content changed and a
         * conditional GET would never hit; freshness comes from the
         * payload's own `time_tag` and our fetch stamp instead. Internal
         * so `SpaceWeatherRepositoryTest` can assert the shape — the same
         * seam [MLatDeltaGrid.raw] opens for the grid tests.
         */
        internal fun forecastRequest(): Request = Request.Builder()
            .url(FORECAST_URL)
            .header("Accept", "application/json")
            // The same descriptive User-Agent as the app's other clients,
            // so an operator sees one identity across both ports rather
            // than anonymous library traffic.
            .header("User-Agent", TerrainElevationService.USER_AGENT)
            .build()

        private const val GRID_ASSET = "spaceweather/mlat_delta_2026.bin"

        private val MIN_INTERVAL_MS: Long =
            (Geomagnetic.FETCH_MIN_INTERVAL_HOURS * 3_600_000.0).toLong()

        /** ~5.5 km of latitude — see [updatePosition]. */
        private const val POSITION_EPSILON_DEG = 0.05

        /** SWPC's column names. The provenance column is called `observed`,
         *  NOT `provenance`, and its VALUES are `observed` / `estimated` /
         *  `predicted` — so the name collides with one of its own values. */
        private const val COLUMN_TIME_TAG = "time_tag"
        private const val COLUMN_KP = "kp"
        private const val COLUMN_OBSERVED = "observed"
        private const val PROVENANCE_OBSERVED = "observed"
        private const val PROVENANCE_ESTIMATED = "estimated"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse the planetary-K-index forecast product. Pure — no clock, no
         * I/O, no state — so every rule below is a named test.
         *
         * The product ships as an array of objects today:
         * ```
         * {"time_tag":"2026-07-18T00:00:00","kp":1.67,
         *  "observed":"observed","noaa_scale":null}
         * ```
         * but it USED to be the header-row array-of-arrays form that its
         * siblings under `/products/` still use, so both are accepted.
         * Both failing returns null, and the caller keeps its cache.
         *
         * Deliberate choices, each one a way this integration would
         * otherwise fail silently:
         *  * rows are SORTED by `time_tag` and never indexed by position —
         *    SWPC's ordering is not consistent across its products, so
         *    sorting removes the failure class instead of testing for it;
         *  * `noaa_scale` is ignored entirely: it is null in every observed
         *    row, and G is derived locally by [Geomagnetic.gScale];
         *  * numbers are parsed as Double, never string-matched — sibling
         *    products emit `2.5999999e+000`;
         *  * a row with a null or unparseable `kp` (or `time_tag`) is
         *    skipped, and the rest survive.
         *
         * An EMPTY array parses successfully into an empty series: that is
         * a well-formed "nothing to say", which resolves to
         * [Freshness.NONE] rather than a crash.
         */
        fun parseKpForecast(body: String, fetchedAtMs: Long): KpSeries? {
            val rows = try {
                json.parseToJsonElement(body) as? JsonArray ?: return null
            } catch (t: Throwable) {
                return null
            }
            if (rows.isEmpty()) return KpSeries(fetchedAtMs, emptyList())

            val points = when (val first = rows.first()) {
                is JsonObject -> parseObjectRows(rows)
                is JsonArray -> parseLegacyRows(rows, first)
                else -> null
            } ?: return null

            return KpSeries(fetchedAtMs, points.sortedBy { it.timeMs })
        }

        /** The current form: one JSON object per bin, keyed by name. */
        private fun parseObjectRows(rows: JsonArray): List<KpPoint>? {
            val points = mutableListOf<KpPoint>()
            for (row in rows) {
                val obj = row as? JsonObject ?: return null
                val timeMs = parseSwpcTimeTagMs(text(obj[COLUMN_TIME_TAG])) ?: continue
                val kp = number(obj[COLUMN_KP]) ?: continue
                points += KpPoint(timeMs, kp, provenance(text(obj[COLUMN_OBSERVED])))
            }
            return points
        }

        /**
         * The legacy form: a header row naming the columns, then one array
         * per bin. Columns are located BY NAME out of that header — their
         * ORDER is not part of any contract we were given.
         */
        private fun parseLegacyRows(rows: JsonArray, header: JsonArray): List<KpPoint>? {
            val names = header.map { text(it)?.lowercase() ?: "" }
            val timeIndex = names.indexOf(COLUMN_TIME_TAG)
            val kpIndex = names.indexOf(COLUMN_KP)
            if (timeIndex < 0 || kpIndex < 0) return null
            val provenanceIndex = names.indexOf(COLUMN_OBSERVED)

            val points = mutableListOf<KpPoint>()
            for (row in rows.drop(1)) {
                val cells = row as? JsonArray ?: return null
                val timeMs = parseSwpcTimeTagMs(text(cells.getOrNull(timeIndex))) ?: continue
                val kp = number(cells.getOrNull(kpIndex)) ?: continue
                val tag = if (provenanceIndex >= 0) text(cells.getOrNull(provenanceIndex)) else null
                points += KpPoint(timeMs, kp, provenance(tag))
            }
            return points
        }

        /**
         * A `time_tag` under `/products` looks like `"2026-07-25T15:00:00"`
         * — no zone suffix — and it IS UTC. Parsed against an explicit
         * [ZoneOffset.UTC] rather than anything that could quietly fall
         * back to the device zone: getting this wrong is silently wrong by
         * up to 14 hours, which is four whole Kp bins.
         *
         * The normalisation is part of the contract too, and iOS
         * `SpaceWeatherService.parseSwpcTimeTag` does exactly the same two
         * steps: the LEGACY rows of this same product write the separator
         * as a space and carry milliseconds
         * (`"2026-07-18 00:00:00.000"`), which `ISO_LOCAL_DATE_TIME` will
         * not read at all — left alone, every legacy row is skipped and
         * the dual-format parse quietly yields ZERO bins from a form we
         * deliberately still support. The fraction is dropped rather than
         * parsed so both platforms land on the same millisecond.
         */
        fun parseSwpcTimeTagMs(timeTag: String?): Long? {
            var raw = timeTag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val separator = raw.indexOf(' ')
            if (separator >= 0) raw = raw.replaceRange(separator, separator + 1, "T")
            val fraction = raw.indexOf('.')
            if (fraction >= 0) raw = raw.substring(0, fraction)
            return try {
                LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (t: Throwable) {
                null
            }
        }

        /** An unrecognised or absent tag reads as `PREDICTED`, captioned
         *  "forecast" — under-claiming, rather than passing an unknown row
         *  off as an observation. */
        private fun provenance(raw: String?): KpProvenance = when (raw?.lowercase()) {
            PROVENANCE_OBSERVED -> KpProvenance.OBSERVED
            PROVENANCE_ESTIMATED -> KpProvenance.ESTIMATED
            else -> KpProvenance.PREDICTED
        }

        /** String content of a primitive cell, or null for JSON `null`, a
         *  missing key or a nested structure. */
        private fun text(element: JsonElement?): String? {
            if (element == null || element is JsonNull) return null
            return (element as? JsonPrimitive)?.content
        }

        /** Numeric content whether the product quoted it or not, so the
         *  legacy form's `"1.67"` and a sibling product's
         *  `2.5999999e+000` both land on the same Double. */
        private fun number(element: JsonElement?): Double? = text(element)?.toDoubleOrNull()
    }
}
