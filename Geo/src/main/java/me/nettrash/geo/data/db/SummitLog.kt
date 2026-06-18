package me.nettrash.geo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A logged ascent of a known peak (auto-detect arrival → manual confirm).
 * Peak fields are denormalised (public bundled-list data) so the trophy entry
 * survives list changes; `measuredAltitude` is the user's own barometric
 * reading at log time. Mirrors the iOS Core Data `SummitLog` entity. Added in
 * Room schema v4 (see MIGRATION_3_4 in [GeoDatabase]).
 */
@Entity(
    tableName = "summit_logs",
    indices = [Index(value = ["loggedDate"])]
)
data class SummitLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peakName: String = "",
    val peakIdentifier: String = "",   // stable de-dup key (name+coords)
    val peakSet: String = "",          // "highest" | "sevenPeaks" | "snowLeopardOfRussia"
    val peakAltitude: Int = 0,         // reference height (m)
    val latitude: Double = 0.0,        // public peak coordinate
    val longitude: Double = 0.0,
    val loggedDate: Long = 0,          // epoch ms
    val measuredAltitude: Double = 0.0, // user's barometric altitude at log time
    val note: String? = null
)
