package me.nettrash.geo.data.repository

import me.nettrash.geo.data.db.HistoryDao
import me.nettrash.geo.data.db.HistoryItem
import me.nettrash.geo.data.model.DataItem
import me.nettrash.geo.data.model.DataPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao
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

    fun addTrackingPoint(
        trackingDataSet: MutableList<DataPoint>,
        barometerHeight: Float,
        gpsAltitude: Float
    ): Triple<List<DataPoint>, Float, Float> {
        if (trackingDataSet.isEmpty()) {
            while (trackingDataSet.size < amountOfValues) {
                trackingDataSet.add(DataPoint(listOf(0f, 0f), trackingFormatter.format(Date())))
            }
        }

        trackingDataSet.add(DataPoint(listOf(barometerHeight, gpsAltitude), trackingFormatter.format(Date())))
        while (trackingDataSet.size > amountOfValues) {
            trackingDataSet.removeAt(0)
        }

        var min = 0f
        var max = 10000f
        val allVals = trackingDataSet.flatMap { it.values }
        val minVal = allVals.minOrNull() ?: 0f
        val maxVal = allVals.maxOrNull() ?: 0f
        if (minVal - 50 > 0f) min = minVal - 50f else if (minVal < 0f) min = minVal
        if (maxVal + 50 < 10000f) max = maxVal + 50f else if (maxVal > 10000f) max = maxVal

        return Triple(trackingDataSet.toList(), min, max)
    }
}
