package me.nettrash.geo.data.model

import kotlinx.serialization.Serializable

/**
 * Cross-process snapshot of barometer + GPS state. Mirrors iOS
 * `Core/InformationToken.swift` so the Android widget, the
 * `BarometerRefreshWorker`, the Wear connectivity bridge, and the
 * future Wear OS app all exchange the same shape over JSON.
 *
 * `gpsLatitude` / `gpsLongitude` default to 0 so older persisted
 * tokens written by Android before lat/lon were added decode without
 * error — this matches the custom `init(from:)` in the iOS struct
 * that tolerates missing fields.
 */
@Serializable
data class InformationToken(
    val recordDate: Long = 0L,
    val gpsAltitude: Double = 0.0,
    val gpsSpeed: Double = 0.0,
    val barPressure: Double = 0.0,
    val barAltitude: Double = 0.0,
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0
)
