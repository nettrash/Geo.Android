package me.nettrash.geo.util

import me.nettrash.geo.data.model.DataPoint

/**
 * Pure real-time tracking-graph math for the Stat tab's top "TRACKING
 * ALTITUDE" chart. Dependency-free (no DAOs, no Android) so it stays
 * unit-testable, following the same convention as [TripStats] /
 * `StormWarning`. Mirrored by iOS `History.addTrackingInformation` — both
 * platforms record a missing series as a NaN gap.
 *
 * Series 0 = barometer, series 1 = GPS. The two are INDEPENDENT: passing
 * `null` for one records [Float.NaN] for that series only. The renderer skips
 * NaN samples (breaking that line) and the auto-scale ignores them, so:
 *
 *  - GPS unavailable (no fix / stale / no altitude) → only the barometer line
 *    is drawn, and it keeps tracking. It does NOT collapse to sea level.
 *  - No barometer hardware → only the GPS line is drawn.
 */
object TrackingGraph {

    /** Width of the rolling window, in samples. */
    const val AMOUNT_OF_VALUES = 30

    const val ALTITUDE_MIN_DEFAULT = 0f
    const val ALTITUDE_MAX_DEFAULT = 10000f

    /**
     * Append one sample to [dataSet] (a rolling window of [AMOUNT_OF_VALUES])
     * and return the new dataset plus its auto-scaled `[min, max]` window.
     *
     * [barometerHeight] and [gpsAltitude] are each optional; `null` records a
     * NaN gap for that series alone.
     */
    fun addPoint(
        dataSet: MutableList<DataPoint>,
        barometerHeight: Float?,
        gpsAltitude: Float?,
        legend: String
    ): Triple<List<DataPoint>, Float, Float> {
        // Seed the window with NaN placeholders so the chart starts empty and
        // fills in from the right as real samples land, instead of showing a
        // misleading flat line at sea level until the zeros scroll out.
        if (dataSet.isEmpty()) {
            while (dataSet.size < AMOUNT_OF_VALUES) {
                dataSet.add(DataPoint(listOf(Float.NaN, Float.NaN), legend))
            }
        }

        dataSet.add(
            DataPoint(
                listOf(barometerHeight ?: Float.NaN, gpsAltitude ?: Float.NaN),
                legend
            )
        )
        while (dataSet.size > AMOUNT_OF_VALUES) {
            dataSet.removeAt(0)
        }

        // Auto-scale over every FINITE sample across both series; NaN gaps are
        // ignored so a missing series never skews the window. With nothing
        // finite yet, keep the default altitude range.
        var min = ALTITUDE_MIN_DEFAULT
        var max = ALTITUDE_MAX_DEFAULT
        val finite = dataSet.flatMap { it.values }.filter { it.isFinite() }
        val minVal = finite.minOrNull()
        val maxVal = finite.maxOrNull()
        if (minVal != null && maxVal != null) {
            if (minVal - 50f > ALTITUDE_MIN_DEFAULT) min = minVal - 50f
            else if (minVal < ALTITUDE_MIN_DEFAULT) min = minVal
            if (maxVal + 50f < ALTITUDE_MAX_DEFAULT) max = maxVal + 50f
            else if (maxVal > ALTITUDE_MAX_DEFAULT) max = maxVal
        }

        return Triple(dataSet.toList(), min, max)
    }
}
