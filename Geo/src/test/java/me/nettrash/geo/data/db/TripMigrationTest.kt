package me.nettrash.geo.data.db

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Migration test for the Trip Recorder (M5c) — the highest-risk part of the
 * feature. Hand-seeds a populated v2 database, opens it through Room with
 * [GeoDatabase.MIGRATION_2_3], and asserts:
 *   1. Room accepts the migrated schema (post-migration validation would
 *      throw if `MIGRATION_2_3`'s CREATE TABLE didn't match the [Trip]
 *      `@Entity` Room derives), and
 *   2. existing `history_items` data is preserved (non-destructive), and
 *   3. the new `trips` table is usable.
 */
@RunWith(RobolectricTestRunner::class)
class TripMigrationTest {

    private val dbName = "trip_migration_test.db"
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate2to3PreservesHistoryAndAddsTrips() = runBlocking {
        context.deleteDatabase(dbName)

        // 1. Seed a v2 database by hand: the history_items table + its
        //    recordDate index (the state MIGRATION_1_2 leaves), user_version=2.
        val seed = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        seed.execSQL(
            "CREATE TABLE IF NOT EXISTS `history_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordDate` INTEGER NOT NULL, " +
                "`barometerAltitude` REAL NOT NULL, " +
                "`barometerPressure` REAL NOT NULL, " +
                "`gpsLatitude` REAL NOT NULL, " +
                "`gpsLongitude` REAL NOT NULL, " +
                "`gpsAltitude` REAL NOT NULL, " +
                "`gpsVelocity` REAL NOT NULL)"
        )
        seed.execSQL("CREATE INDEX IF NOT EXISTS `index_history_items_recordDate` ON `history_items` (`recordDate`)")
        seed.execSQL(
            "INSERT INTO history_items " +
                "(recordDate, barometerAltitude, barometerPressure, gpsLatitude, gpsLongitude, gpsAltitude, gpsVelocity) " +
                "VALUES (1700000000000, 123.0, 101.0, 45.0, 8.0, 120.0, 1.0)"
        )
        seed.execSQL("PRAGMA user_version = 2")
        seed.close()

        // 2. Open via Room with the migrations registered → runs 2->3 and
        //    validates the resulting schema against the entities.
        val db = Room.databaseBuilder(context, GeoDatabase::class.java, dbName)
            .addMigrations(GeoDatabase.MIGRATION_1_2, GeoDatabase.MIGRATION_2_3)
            .build()

        // 3. Existing history survived the migration.
        val items = db.historyDao().getItemsSince(0)
        assertThat(items).hasSize(1)
        assertThat(items[0].barometerAltitude).isEqualTo(123.0)

        // 4. The new trips table is usable.
        db.tripDao().insert(Trip(name = "Test outing", startDate = 10, endDate = 20, totalAscent = 50.0))
        val trips = db.tripDao().getAll()
        assertThat(trips).hasSize(1)
        assertThat(trips[0].name).isEqualTo("Test outing")
        assertThat(trips[0].totalAscent).isEqualTo(50.0)

        db.close()
    }
}
