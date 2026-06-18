package me.nettrash.geo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SummitLogDao {
    @Insert
    suspend fun insert(log: SummitLog): Long

    @Query("SELECT * FROM summit_logs ORDER BY loggedDate DESC")
    suspend fun getAll(): List<SummitLog>

    @Update
    suspend fun update(log: SummitLog)

    @Delete
    suspend fun delete(log: SummitLog)

    /** For the proximity-prompt de-dup: has this peak been logged since [sinceMs]? */
    @Query("SELECT COUNT(*) FROM summit_logs WHERE peakIdentifier = :peakId AND loggedDate >= :sinceMs")
    suspend fun countRecentForPeak(peakId: String, sinceMs: Long): Int
}
