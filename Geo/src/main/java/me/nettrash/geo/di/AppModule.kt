package me.nettrash.geo.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.nettrash.geo.data.db.GeoDatabase
import me.nettrash.geo.data.db.HistoryDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GeoDatabase {
        return Room.databaseBuilder(
            context,
            GeoDatabase::class.java,
            "geo_database"
        ).addMigrations(GeoDatabase.MIGRATION_1_2).build()
    }

    @Provides
    fun provideHistoryDao(database: GeoDatabase): HistoryDao {
        return database.historyDao()
    }
}
