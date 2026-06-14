package me.nettrash.geo.sensor

/**
 * One barometric sample for the storm-warning tendency fit. Carries the
 * RAW station pressure (kPa, already clamped 30–110 upstream) and the GPS
 * altitude (m) so the fit can remove the altitude-driven pressure change
 * before classifying the weather tendency.
 */
data class PressureSample(
    val dateMs: Long,
    val pressureKpa: Double,
    val altitudeM: Double
)

/** Coarse classification of the de-trended 3-hour barometric tendency. */
enum class PressureTrendClass {
    UNKNOWN,       // not enough data to classify — never alerts
    RISING,
    STEADY,
    FALLING,       // a real but sub-threshold fall — no alert
    FALLING_FAST   // crosses the storm threshold — alert-worthy
}

/**
 * Result of a tendency fit: the class plus the signed de-trended change
 * in hectopascals over the 3-hour window (negative = falling).
 */
data class PressureTrend(
    val classification: PressureTrendClass,
    val changeHpaOver3h: Double
) {
    val isAlert: Boolean get() = classification == PressureTrendClass.FALLING_FAST

    companion object {
        val UNKNOWN = PressureTrend(PressureTrendClass.UNKNOWN, 0.0)
    }
}

/**
 * Pure, dependency-free storm-warning math. Kept byte-identical to the
 * iOS `StormWarning` enum so both platforms classify a given history the
 * same way (the cross-platform parity invariant). The fit is orchestrated
 * from [me.nettrash.geo.data.repository.HistoryRepository] per the v1.1
 * plan; the [me.nettrash.geo.worker.BarometerRefreshWorker] path feeds it
 * the merged Room + buffered samples and turns an alert into a
 * `NotificationManagerCompat` notification.
 */
object StormWarning {
    // ── Shared constants — MUST match iOS StormWarning ──────────────
    const val WINDOW_HOURS = 3.0
    const val MIN_SAMPLES = 4
    const val MIN_SPAN_HOURS = 1.5
    const val STEADY_BAND_HPA_OVER_3H = 1.0
    const val ALERT_DROP_HPA_OVER_3H = 3.0
    const val SEVERE_DROP_HPA_OVER_3H = 6.0
    const val NOTIFICATION_COOLDOWN_HOURS = 3.0
    const val ALT_CLAMP_LOW = -500.0
    const val ALT_CLAMP_HIGH = 9000.0
    private const val BAROMETRIC_SCALE = 44330.0
    private const val BAROMETRIC_EXPONENT = 5.255

    private const val MS_PER_HOUR = 3_600_000.0

    /** Standard-atmosphere pressure ratio P(alt)/P(0) at [altM], with the
     *  altitude clamped to the app's plausible domain so a noisy GPS
     *  reading can't blow up the correction factor. */
    private fun pressureRatio(altM: Double): Double {
        val a = altM.coerceIn(ALT_CLAMP_LOW, ALT_CLAMP_HIGH)
        return Math.pow(1.0 - a / BAROMETRIC_SCALE, BAROMETRIC_EXPONENT)
    }

    /**
     * De-trended 3-hour barometric tendency for [samples] evaluated at
     * [nowMs]. Reads RAW station pressure and removes the GPS-altitude-
     * driven pressure component (correcting every sample to the most
     * recent sample's altitude) before a least-squares slope, so a climb
     * doesn't read as a falling barometer. Returns [PressureTrend.UNKNOWN]
     * when the window is too thin to classify.
     */
    fun tendency(samples: List<PressureSample>, nowMs: Long): PressureTrend {
        val windowStartMs = nowMs - (WINDOW_HOURS * MS_PER_HOUR).toLong()
        val win = samples
            .filter { it.dateMs in windowStartMs..nowMs && it.pressureKpa > 0 && it.pressureKpa.isFinite() }
            .sortedBy { it.dateMs }

        if (win.size < MIN_SAMPLES) return PressureTrend.UNKNOWN
        val first = win.first()
        val last = win.last()
        val spanHours = (last.dateMs - first.dateMs) / MS_PER_HOUR
        if (spanHours < MIN_SPAN_HOURS) return PressureTrend.UNKNOWN

        // Correct each raw station pressure to the most-recent sample's
        // altitude so the fitted slope reflects weather, not climbing.
        val refRatio = pressureRatio(last.altitudeM)
        val n = win.size.toDouble()
        val t0 = first.dateMs
        var sumT = 0.0
        var sumY = 0.0
        var sumTT = 0.0
        var sumTY = 0.0
        for (s in win) {
            val t = (s.dateMs - t0) / MS_PER_HOUR                              // hours
            val y = s.pressureKpa * 10.0 * (refRatio / pressureRatio(s.altitudeM)) // hPa
            sumT += t; sumY += y; sumTT += t * t; sumTY += t * y
        }
        val meanT = sumT / n
        val meanY = sumY / n
        val den = sumTT - n * meanT * meanT
        if (den <= 0.0) return PressureTrend.UNKNOWN
        val slope = (sumTY - n * meanT * meanY) / den                         // hPa per hour
        val change3h = slope * 3.0                                            // signed hPa / 3 h

        val cls = when {
            change3h <= -ALERT_DROP_HPA_OVER_3H -> PressureTrendClass.FALLING_FAST
            change3h <= -STEADY_BAND_HPA_OVER_3H -> PressureTrendClass.FALLING
            change3h >= STEADY_BAND_HPA_OVER_3H -> PressureTrendClass.RISING
            else -> PressureTrendClass.STEADY
        }
        return PressureTrend(cls, change3h)
    }
}
