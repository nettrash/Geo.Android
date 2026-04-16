package me.nettrash.geo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [HistoryItem::class], version = 1, exportSchema = false)
abstract class GeoDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
