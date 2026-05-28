package me.nettrash.geo.widget

import android.content.Context
import me.nettrash.geo.data.model.InformationToken
import me.nettrash.geo.data.snapshot.SharedSnapshotStore

/**
 * Thin compatibility shim around [SharedSnapshotStore]. Kept so the
 * Glance widget composables don't have to know about
 * [InformationToken] directly. New call sites should prefer
 * [SharedSnapshotStore] which exposes the full cross-process API
 * (current snapshot + ring buffer).
 *
 * Mirrors the read shape that iOS widget views expect from
 * `SharedSnapshotStore.readCurrent()`.
 */
object WidgetDataStore {

    data class Snapshot(
        val pressureKpa: Double = 0.0,
        val barAltitude: Double = 0.0,
        val gpsAltitude: Double = 0.0,
        val gpsSpeed:    Double = 0.0,
        val gpsLat:      Double = 0.0,
        val gpsLon:      Double = 0.0,
        val updatedAt:   Long   = 0L
    )

    fun write(
        context: Context,
        pressureKpa: Double,
        barAltitude: Double,
        gpsAltitude: Double,
        gpsSpeed: Double,
        gpsLat: Double,
        gpsLon: Double
    ) {
        SharedSnapshotStore.write(
            context = context,
            token = InformationToken(
                recordDate = System.currentTimeMillis(),
                gpsAltitude = gpsAltitude,
                gpsSpeed = gpsSpeed,
                barPreassure = pressureKpa,
                barAltitude = barAltitude,
                gpsLatitude = gpsLat,
                gpsLongitude = gpsLon
            )
        )
    }

    fun read(context: Context): Snapshot {
        val token = SharedSnapshotStore.readCurrent(context)
            ?: return Snapshot()
        return Snapshot(
            pressureKpa = token.barPreassure,
            barAltitude = token.barAltitude,
            gpsAltitude = token.gpsAltitude,
            gpsSpeed    = token.gpsSpeed,
            gpsLat      = token.gpsLatitude,
            gpsLon      = token.gpsLongitude,
            updatedAt   = token.recordDate
        )
    }
}
