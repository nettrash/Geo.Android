package me.nettrash.geo.data.model

import kotlinx.serialization.Serializable

/**
 * Cross-process snapshot of barometer + GPS state. Mirrors iOS
 * `Core/InformationToken.swift` so the Android widget, the
 * `BarometerRefreshWorker`, the Wear connectivity bridge, and the
 * Wear OS app all exchange the same shape over JSON.
 *
 * **Field naming**: `barPreassure` is intentionally misspelled to
 * match the iOS source field-for-field. The on-wire JSON key has to
 * stay `barPreassure` so the same payload round-trips between iOS
 * and Android — fixing it on one side without the other would break
 * cross-platform connectivity. We carry the typo through to the
 * Kotlin property name as well rather than papering over it with
 * `@SerialName`, so anyone grepping the codebase finds both ends.
 *
 * `gpsLatitude` / `gpsLongitude` default to 0 so older persisted
 * tokens written before lat/lon were added decode cleanly — matches
 * the custom `init(from:)` in the iOS struct that tolerates missing
 * fields.
 */
@Serializable
data class InformationToken(
    val recordDate: Long = 0L,
    val gpsAltitude: Double = 0.0,
    val gpsSpeed: Double = 0.0,
    val barPreassure: Double = 0.0,
    val barAltitude: Double = 0.0,
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0
)
