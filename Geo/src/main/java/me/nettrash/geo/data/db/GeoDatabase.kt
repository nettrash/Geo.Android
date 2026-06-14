package me.nettrash.geo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryItem::class], version = 2, exportSchema = false)
abstract class GeoDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

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
    }
}
