package me.nettrash.geo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

// recordDate is indexed: every history read filters/sorts on it
// (getItemsSince range, getRecentItems ORDER BY, findByRecordDate
// lookup), so the index turns those scans into index lookups as the
// store grows. See migration MIGRATION_1_2 in GeoDatabase.
@Entity(
    tableName = "history_items",
    indices = [Index(value = ["recordDate"])]
)
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordDate: Long = System.currentTimeMillis(),
    val barometerAltitude: Double = 0.0,
    val barometerPressure: Double = 0.0,
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0,
    val gpsAltitude: Double = 0.0,
    val gpsVelocity: Double = 0.0
)
