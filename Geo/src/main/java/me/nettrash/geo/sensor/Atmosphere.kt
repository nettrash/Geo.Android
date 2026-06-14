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
}
