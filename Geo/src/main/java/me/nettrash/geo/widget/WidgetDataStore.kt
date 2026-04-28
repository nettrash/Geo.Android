package me.nettrash.geo.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper used by both the main app (writer)
 * and the Glance widget (reader) to exchange the latest sensor snapshot.
 */
object WidgetDataStore {

    private const val PREFS_NAME = "me.nettrash.geo.widget_prefs"

    // Keys
    const val KEY_PRESSURE_KPA  = "pressure_kpa"
    const val KEY_BAR_ALTITUDE  = "bar_altitude"
    const val KEY_GPS_ALTITUDE  = "gps_altitude"
    const val KEY_GPS_SPEED     = "gps_speed"
    const val KEY_GPS_LAT       = "gps_lat"
    const val KEY_GPS_LON       = "gps_lon"
    const val KEY_UPDATED_AT    = "updated_at"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun write(
        context: Context,
        pressureKpa: Double,
        barAltitude: Double,
        gpsAltitude: Double,
        gpsSpeed: Double,
        gpsLat: Double,
        gpsLon: Double
    ) {
        prefs(context).edit()
            .putFloat(KEY_PRESSURE_KPA, pressureKpa.toFloat())
            .putFloat(KEY_BAR_ALTITUDE, barAltitude.toFloat())
            .putFloat(KEY_GPS_ALTITUDE, gpsAltitude.toFloat())
            .putFloat(KEY_GPS_SPEED,    gpsSpeed.toFloat())
            .putFloat(KEY_GPS_LAT,      gpsLat.toFloat())
            .putFloat(KEY_GPS_LON,      gpsLon.toFloat())
            .putLong(KEY_UPDATED_AT,    System.currentTimeMillis())
            .apply()
    }

    data class Snapshot(
        val pressureKpa: Double = 0.0,
        val barAltitude: Double = 0.0,
        val gpsAltitude: Double = 0.0,
        val gpsSpeed:    Double = 0.0,
        val gpsLat:      Double = 0.0,
        val gpsLon:      Double = 0.0,
        val updatedAt:   Long   = 0L
    )

    fun read(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            pressureKpa = p.getFloat(KEY_PRESSURE_KPA, 0f).toDouble(),
            barAltitude = p.getFloat(KEY_BAR_ALTITUDE,  0f).toDouble(),
            gpsAltitude = p.getFloat(KEY_GPS_ALTITUDE,  0f).toDouble(),
            gpsSpeed    = p.getFloat(KEY_GPS_SPEED,      0f).toDouble(),
            gpsLat      = p.getFloat(KEY_GPS_LAT,        0f).toDouble(),
            gpsLon      = p.getFloat(KEY_GPS_LON,        0f).toDouble(),
            updatedAt   = p.getLong(KEY_UPDATED_AT,       0L)
        )
    }
}
