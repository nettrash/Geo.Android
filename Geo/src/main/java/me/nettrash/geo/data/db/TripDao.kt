package me.nettrash.geo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: Trip): Long

    @Query("SELECT * FROM trips ORDER BY startDate DESC")
    suspend fun getAll(): List<Trip>

    @Delete
    suspend fun delete(trip: Trip)
}
