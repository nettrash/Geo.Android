package me.nettrash.geo.data.repository

import me.nettrash.geo.data.db.HistoryDao
import me.nettrash.geo.data.db.HistoryItem
import me.nettrash.geo.data.db.SummitLog
import me.nettrash.geo.data.db.SummitLogDao
import me.nettrash.geo.data.db.Trip
import me.nettrash.geo.data.db.TripDao
import me.nettrash.geo.data.model.DataItem
import me.nettrash.geo.data.model.DataPoint
import me.nettrash.geo.sensor.PressureSample
import me.nettrash.geo.sensor.PressureTrend
import me.nettrash.geo.sensor.StormWarning
import me.nettrash.geo.util.TrackingGraph
import me.nettrash.geo.util.TripSample
import me.nettrash.geo.util.TripStats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao,
    private val tripDao: TripDao,
    private val summitLogDao: SummitLogDao
) {
    private val dateFormatter = SimpleDateFormat("MMM d", Locale.getDefault())
    private val trackingFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val amountOfValues = 30
    private val numberOfDays = 30

    /** How long a HistoryItem is kept before retention pruning removes it.
     *  ~366 days so a full year (including a leap day) is always available
     *  to the statistics views. Mirrors iOS History.retentionDays. */
    private val retentionDays = 366

    suspend fun insert(item: HistoryItem) {
        historyDao.insert(item)
    }

    /** Drop history older than the retention window. Run once at startup. */
    suspend fun prune() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -retentionDays)
        historyDao.deleteOlderThan(cal.timeInMillis)
    }

    /** Delete all recorded history. Backs the "Clear history" action. */
    suspend fun clearAll() {
        historyDao.deleteAll()
    }

    suspend fun getItemsSince(days: Int = numberOfDays): List<HistoryItem> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return historyDao.getItemsSince(cal.timeInMillis)
    }

    suspend fun getRecentItems(limit: Int = 200): List<HistoryItem> {
        return historyDao.getRecentItems(limit)
    }

    /** Used by the snapshot-buffer backfill to dedup by timestamp. */
    suspend fun findByRecordDate(recordDate: Long): HistoryItem? {
        return historyDao.findByRecordDate(recordDate)
    }

    // ── Trip Recorder (M5c) ──────────────────────────────────────

    /** Recorded samples in `[start, end]` (recordDate-ascending, indexed). */
    suspend fun getItemsBetween(start: Long, end: Long): List<HistoryItem> =
        historyDao.getItemsBetween(start, end)

    /** History rows in `[start, end]` mapped to neutral stat samples
     *  (barometric altitude for precise relative change). */
    suspend fun tripSamples(start: Long, end: Long): List<TripSample> =
        getItemsBetween(start, end).map {
            TripSample(it.recordDate, it.barometerAltitude, it.gpsLatitude, it.gpsLongitude, it.gpsVelocity)
        }

    /** Barometric-altitude elevation profile for `[start, end]`, downsampled
     *  to at most [maxPoints] points for charting. */
    suspend fun tripElevationProfile(start: Long, end: Long, maxPoints: Int = 120): List<Double> {
        if (maxPoints <= 0) return emptyList()   // "at most maxPoints" — 0 means none
        val altitudes = getItemsBetween(start, end).map { it.barometerAltitude }
        if (altitudes.size <= maxPoints) return altitudes
        val stride = altitudes.size.toDouble() / maxPoints
        return (0 until maxPoints).map { altitudes[(it * stride).toInt()] }
    }

    /** Create + persist a [Trip] whose summary is computed once from the
     *  samples in `[start, end]` and denormalised so it survives history
     *  pruning. Returns the saved trip (with its assigned id). */
    suspend fun saveTrip(name: String, start: Long, end: Long): Trip {
        val summary = TripStats.summary(tripSamples(start, end))
        val trip = Trip(
            name = name,
            startDate = start,
            endDate = end,
            totalAscent = summary.totalAscent,
            totalDescent = summary.totalDescent,
            maxAltitude = summary.maxAltitude,
            minAltitude = summary.minAltitude,
            distance = summary.distance,
            movingTime = summary.movingTime
        )
        val id = tripDao.insert(trip)
        return trip.copy(id = id)
    }

    /** All saved trips, newest first. */
    suspend fun getTrips(): List<Trip> = tripDao.getAll()

    suspend fun deleteTrip(trip: Trip) = tripDao.delete(trip)

    // ── Summit log ───────────────────────────────────────────────
    suspend fun saveSummitLog(log: SummitLog): Long = summitLogDao.insert(log)

    suspend fun getSummitLogs(): List<SummitLog> = summitLogDao.getAll()

    suspend fun updateSummitLog(log: SummitLog) = summitLogDao.update(log)

    suspend fun deleteSummitLog(log: SummitLog) = summitLogDao.delete(log)

    /** True if [peakId] was logged within the last [hours] (proximity de-dup). */
    suspend fun summitLoggedRecently(peakId: String, hours: Long): Boolean {
        val since = System.currentTimeMillis() - hours * 3_600_000L
        return summitLogDao.countRecentForPeak(peakId, since) > 0
    }

    /**
     * De-trended 3-hour barometric tendency (storm warning, M5a). Reads
     * the last [StormWarning.WINDOW_HOURS] of RAW `barometerPressure` +
     * `gpsAltitude` from Room, merges any [extraSamples] the caller holds
     * that aren't yet in Room (the not-yet-drained background snapshot
     * buffer plus the freshest sample), dedups by exact timestamp, and
     * runs the shared [StormWarning] fit. The fit is byte-identical to
     * iOS `StormWarning.tendency`.
     */
    suspend fun pressureTendency(extraSamples: List<PressureSample>, nowMs: Long): PressureTrend {
        val cutoff = nowMs - (StormWarning.WINDOW_HOURS * 3_600_000.0).toLong()
        val roomSamples = historyDao.getItemsSince(cutoff).map {
            PressureSample(it.recordDate, it.barometerPressure, it.gpsAltitude)
        }
        val merged = (roomSamples + extraSamples)
            .filter { it.pressureKpa > 0 }
            .associateBy { it.dateMs }   // dedup by exact timestamp
            .values
            .sortedBy { it.dateMs }
        return StormWarning.tendency(merged, nowMs)
    }

    fun buildPressureDataSet(items: List<HistoryItem>): Triple<List<DataItem>, Float, Float> {
        if (items.isEmpty()) return Triple(emptyList(), 0f, 1000f)

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(amountOfValues - 1))
        val minDate = cal.timeInMillis

        val dataSet = mutableListOf<DataItem>()
        for (idx in 0 until amountOfValues) {
            cal.timeInMillis = minDate
            cal.add(Calendar.DAY_OF_YEAR, idx)
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = cal.timeInMillis

            val dayItems = items.filter { it.recordDate in dayStart until dayEnd }
            val minPressure = dayItems.minOfOrNull { it.barometerPressure }?.toFloat() ?: 0f
            dataSet.add(DataItem(minPressure * 7.50062f, dateFormatter.format(Date(dayStart))))
        }

        while (dataSet.size < amountOfValues) {
            dataSet.add(DataItem(0f, dateFormatter.format(Date())))
        }

        var min = 0f
        var max = 1000f
        val minVal = dataSet.minOfOrNull { it.value } ?: 0f
        val maxVal = dataSet.maxOfOrNull { it.value } ?: 0f
        if (minVal - 50 > 0f) min = minVal - 50f
        if (maxVal + 50 < 1000f) max = maxVal + 50f

        return Triple(dataSet, min, max)
    }

    fun buildBarometerAltitudeDataSet(items: List<HistoryItem>): Triple<List<DataItem>, Float, Float> {
        if (items.isEmpty()) return Triple(emptyList(), 0f, 10000f)

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(amountOfValues - 1))
        val minDate = cal.timeInMillis

        val dataSet = mutableListOf<DataItem>()
        for (idx in 0 until amountOfValues) {
            cal.timeInMillis = minDate
            cal.add(Calendar.DAY_OF_YEAR, idx)
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = cal.timeInMillis

            val dayItems = items.filter { it.recordDate in dayStart until dayEnd }
            val maxAlt = dayItems.maxOfOrNull { it.barometerAltitude }?.toFloat() ?: 0f
            dataSet.add(DataItem(maxAlt, dateFormatter.format(Date(dayStart))))
        }

        while (dataSet.size < amountOfValues) {
            dataSet.add(DataItem(0f, dateFormatter.format(Date())))
        }

        var min = 0f
        var max = 10000f
        val minVal = dataSet.minOfOrNull { it.value } ?: 0f
        val maxVal = dataSet.maxOfOrNull { it.value } ?: 0f
        if (minVal - 50 > 0f) min = minVal - 50f else if (minVal < 0f) min = minVal
        if (maxVal + 50 < 10000f) max = maxVal + 50f else if (maxVal > 10000f) max = maxVal

        return Triple(dataSet, min, max)
    }

    fun buildGPSAltitudeDataSet(items: List<HistoryItem>): Triple<List<DataItem>, Float, Float> {
        if (items.isEmpty()) return Triple(emptyList(), 0f, 10000f)

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(amountOfValues - 1))
        val minDate = cal.timeInMillis

        val dataSet = mutableListOf<DataItem>()
        for (idx in 0 until amountOfValues) {
            cal.timeInMillis = minDate
            cal.add(Calendar.DAY_OF_YEAR, idx)
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = cal.timeInMillis

            val dayItems = items.filter { it.recordDate in dayStart until dayEnd }
            val maxAlt = dayItems.maxOfOrNull { it.gpsAltitude }?.toFloat() ?: 0f
            dataSet.add(DataItem(maxAlt, dateFormatter.format(Date(dayStart))))
        }

        while (dataSet.size < amountOfValues) {
            dataSet.add(DataItem(0f, dateFormatter.format(Date())))
        }

        var min = 0f
        var max = 10000f
        val minVal = dataSet.minOfOrNull { it.value } ?: 0f
        val maxVal = dataSet.maxOfOrNull { it.value } ?: 0f
        if (minVal - 50 > 0f) min = minVal - 50f else if (minVal < 0f) min = minVal
        if (maxVal + 50 < 10000f) max = maxVal + 50f else if (maxVal > 10000f) max = maxVal

        return Triple(dataSet, min, max)
    }

    /**
     * Append one real-time tracking sample. [barometerHeight] and [gpsAltitude]
     * are each optional: a `null` records a NaN gap for that series, so its line
     * breaks (and auto-scaling ignores it) rather than collapsing to sea level.
     * This is what keeps the barometer line live when GPS is unavailable.
     *
     * The math lives in [TrackingGraph] so it stays pure + unit-testable
     * (mirrors iOS `History.addTrackingInformation`).
     */
    fun addTrackingPoint(
        trackingDataSet: MutableList<DataPoint>,
        barometerHeight: Float?,
        gpsAltitude: Float?
    ): Triple<List<DataPoint>, Float, Float> =
        TrackingGraph.addPoint(
            trackingDataSet,
            barometerHeight,
            gpsAltitude,
            trackingFormatter.format(Date())
        )
}
