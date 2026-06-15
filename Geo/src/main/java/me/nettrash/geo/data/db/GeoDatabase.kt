package me.nettrash.geo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryItem::class, Trip::class, SummitLog::class], version = 4, exportSchema = false)
abstract class GeoDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun tripDao(): TripDao
    abstract fun summitLogDao(): SummitLogDao

    companion object {
        /**
         * v1 -> v2: add an index on `recordDate` (see A23). NON-destructive
         * — it only creates the index, so existing user history is
         * preserved (we must NOT use a destructive fallback here). The
         * index name must match the one Room derives from the @Entity
         * `Index("recordDate")` declaration, otherwise validation fails
         * after migration.
         *
         * Wire this into the Room builder, e.g.:
         * `Room.databaseBuilder(...).addMigrations(GeoDatabase.MIGRATION_1_2).build()`
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_history_items_recordDate` " +
                        "ON `history_items` (`recordDate`)"
                )
            }
        }

        /**
         * v2 -> v3: add the `trips` table for the Trip Recorder (M5c).
         * NON-destructive — only creates a new table + its index, so all
         * existing history is preserved. The `CREATE TABLE` / index name
         * must match exactly what Room derives from the [Trip] `@Entity`,
         * otherwise post-migration schema validation fails.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trips` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`startDate` INTEGER NOT NULL, " +
                        "`endDate` INTEGER NOT NULL, " +
                        "`totalAscent` REAL NOT NULL, " +
                        "`totalDescent` REAL NOT NULL, " +
                        "`maxAltitude` REAL NOT NULL, " +
                        "`minAltitude` REAL NOT NULL, " +
                        "`distance` REAL NOT NULL, " +
                        "`movingTime` REAL NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trips_startDate` ON `trips` (`startDate`)"
                )
            }
        }

        /**
         * v3 -> v4: add the `summit_logs` table for the Summit log. NON-
         * destructive — only creates a new table + its index, so existing
         * history and trips are preserved. Column names/types/nullability and
         * the index name MUST match exactly what Room derives from the
         * [SummitLog] `@Entity`, or post-migration schema validation fails.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `summit_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`peakName` TEXT NOT NULL, " +
                        "`peakIdentifier` TEXT NOT NULL, " +
                        "`peakSet` TEXT NOT NULL, " +
                        "`peakAltitude` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`loggedDate` INTEGER NOT NULL, " +
                        "`measuredAltitude` REAL NOT NULL, " +
                        "`note` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_summit_logs_loggedDate` ON `summit_logs` (`loggedDate`)"
                )
            }
        }
    }
}
