package me.nettrash.geo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "history_items")
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
