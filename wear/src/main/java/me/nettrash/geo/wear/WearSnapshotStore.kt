package me.nettrash.geo.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Receives inbound `InformationToken` payloads pushed by the phone
 * over the Wearable Data Layer. The Watch UI subscribes to [token]
 * to show last-known GPS context alongside the watch-local barometer
 * readings, matching iOS Watch ContentView's "iphoneGPS*" fields.
 *
 * Kept as a process-singleton object so the listener service (which
 * runs in its own process binding) can push directly into a
 * `MutableStateFlow` the UI observes without needing a database or
 * broadcast.
 */
object WearSnapshotStore {
    private val _token = MutableStateFlow<WearInformationToken?>(null)
    val token: StateFlow<WearInformationToken?> = _token.asStateFlow()

    fun update(token: WearInformationToken) {
        _token.value = token
    }
}

/**
 * Wire-compatible snapshot DTO. Matches phone-side
 * `me.nettrash.geo.data.model.InformationToken` field-for-field so
 * both sides can decode the same JSON blob over the Wearable Data
 * Layer.
 */
@Serializable
data class WearInformationToken(
    val recordDate: Long = 0L,
    val gpsAltitude: Double = 0.0,
    val gpsSpeed: Double = 0.0,
    val barPreassure: Double = 0.0,
    val barAltitude: Double = 0.0,
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0
)
