package me.nettrash.geo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(item: HistoryItem)

    @Query("SELECT * FROM history_items WHERE recordDate >= :cutoff AND barometerPressure > 0 ORDER BY recordDate ASC")
    suspend fun getItemsSince(cutoff: Long): List<HistoryItem>

    @Query("SELECT * FROM history_items ORDER BY recordDate DESC LIMIT :limit")
    suspend fun getRecentItems(limit: Int): List<HistoryItem>

    @Query("SELECT * FROM history_items WHERE recordDate = :recordDate LIMIT 1")
    suspend fun findByRecordDate(recordDate: Long): HistoryItem?

    @Query("DELETE FROM history_items WHERE recordDate < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM history_items")
    suspend fun deleteAll()
}
