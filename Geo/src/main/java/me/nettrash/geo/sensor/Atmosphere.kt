package me.nettrash.geo.sensor

/**
 * Standard-atmosphere helpers for turning barometric pressure into
 * altitude. The :Geo and :wear modules are separate Gradle modules
 * and cannot share a class, so an identical copy lives in
 * `me.nettrash.geo.wear.Atmosphere`.
 */
object Atmosphere {
    const val SEA_LEVEL_KPA = 101.325

    /** Height of Mount Everest (m); the "% of Everest" altitude reference. */
    const val EVEREST_HEIGHT_M = 8848.86

    /** Lapse-rate barometric altitude (m); clamps pressure to 30–110 kPa first (#11). */
    fun altitude(pressureKpa: Double, referenceKpa: Double = SEA_LEVEL_KPA): Double {
        val pc = pressureKpa.coerceIn(30.0, 110.0)
        val p0c = referenceKpa.coerceIn(30.0, 110.0)
        return 44330.0 * (1.0 - Math.pow(pc / p0c, 1.0 / 5.255))
    }

    /** Linear-decay window (hours) for a manual "I am at X m" calibration. The
     *  offset is fully applied when fresh and decays to zero over this window,
     *  so a stale calibration can't silently re-bias as weather drifts. */
    const val CALIBRATION_DECAY_HOURS = 6.0

    /**
     * Inverse of [altitude]: the sea-level reference pressure (kPa) that makes
     * the lapse-rate formula output [knownAltitudeM] for the given live
     * station pressure. The exact algebraic inverse —
     * `p0 = p / (1 − h/44330)^5.255` — so a value fed back into the forward
     * helper reproduces the entered altitude. Kept identical to iOS
     * `Atmosphere.solveReferencePressure`.
     */
    fun solveReferencePressure(knownAltitudeM: Double, livePressureKpa: Double): Double {
        val pc = livePressureKpa.coerceIn(30.0, 110.0)
        val base = 1.0 - knownAltitudeM / 44330.0
        if (base <= 0.0) return SEA_LEVEL_KPA   // h < 44330 m always on Earth
        return pc / Math.pow(base, 5.255)
    }

    /** Weight in [0, 1] of a calibration of age [ageSeconds]: 1 when fresh,
     *  decaying linearly to 0 at [CALIBRATION_DECAY_HOURS]. */
    fun calibrationWeight(ageSeconds: Double): Double {
        if (ageSeconds <= 0.0) return 1.0
        return (1.0 - ageSeconds / (CALIBRATION_DECAY_HOURS * 3600.0)).coerceIn(0.0, 1.0)
    }
}
