package me.nettrash.geo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recorded outing: a named window over the always-on sample stream with
 * its summary stats denormalised at save time (so they survive history
 * pruning). Mirrors the iOS Core Data `Trip` entity. Added in Room
 * schema v3 (see MIGRATION_2_3 in [GeoDatabase]).
 */
@Entity(
    tableName = "trips",
    indices = [Index(value = ["startDate"])]
)
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val startDate: Long = 0,   // epoch ms
    val endDate: Long = 0,     // epoch ms
    val totalAscent: Double = 0.0,
    val totalDescent: Double = 0.0,
    val maxAltitude: Double = 0.0,
    val minAltitude: Double = 0.0,
    val distance: Double = 0.0,
    val movingTime: Double = 0.0   // seconds
)
