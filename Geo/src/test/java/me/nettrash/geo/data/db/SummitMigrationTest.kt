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
 * Migration test for the Summit log (v3 -> v4). Hand-seeds a populated v3
 * database (history_items + trips), opens it through Room with
 * [GeoDatabase.MIGRATION_3_4], and asserts:
 *   1. Room accepts the migrated schema (post-migration validation throws if
 *      MIGRATION_3_4's CREATE TABLE doesn't byte-match the [SummitLog]
 *      `@Entity` Room derives), and
 *   2. existing history + trips are preserved (non-destructive), and
 *   3. the new `summit_logs` table is usable.
 */
@RunWith(RobolectricTestRunner::class)
class SummitMigrationTest {

    private val dbName = "summit_migration_test.db"
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate3to4PreservesDataAndAddsSummitLogs() = runBlocking {
        context.deleteDatabase(dbName)

        // 1. Seed a v3 database by hand: history_items + trips (the state
        //    MIGRATION_2_3 leaves), user_version=3.
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
        seed.execSQL(
            "CREATE TABLE IF NOT EXISTS `trips` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER NOT NULL, " +
                "`totalAscent` REAL NOT NULL, `totalDescent` REAL NOT NULL, `maxAltitude` REAL NOT NULL, " +
                "`minAltitude` REAL NOT NULL, `distance` REAL NOT NULL, `movingTime` REAL NOT NULL)"
        )
        seed.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_startDate` ON `trips` (`startDate`)")
        seed.execSQL("INSERT INTO trips (name, startDate, endDate, totalAscent, totalDescent, maxAltitude, minAltitude, distance, movingTime) VALUES ('Old trip', 1, 2, 0, 0, 0, 0, 0, 0)")
        seed.execSQL("PRAGMA user_version = 3")
        seed.close()

        // 2. Open via Room with the migrations registered → runs 3->4 and
        //    validates the resulting schema against the entities.
        val db = Room.databaseBuilder(context, GeoDatabase::class.java, dbName)
            .addMigrations(
                GeoDatabase.MIGRATION_1_2,
                GeoDatabase.MIGRATION_2_3,
                GeoDatabase.MIGRATION_3_4
            )
            .build()

        // 3. Existing history + trips survived.
        assertThat(db.historyDao().getItemsSince(0)).hasSize(1)
        assertThat(db.tripDao().getAll()).hasSize(1)

        // 4. The new summit_logs table is usable.
        db.summitLogDao().insert(
            SummitLog(
                peakName = "Mount Elbrus",
                peakIdentifier = "Mount Elbrus@43.3550,42.4392",
                peakSet = "sevenPeaks",
                peakAltitude = 5642,
                latitude = 43.3550,
                longitude = 42.4392,
                loggedDate = 1700000000000,
                measuredAltitude = 5640.0,
                note = "windy"
            )
        )
        val logs = db.summitLogDao().getAll()
        assertThat(logs).hasSize(1)
        assertThat(logs[0].peakName).isEqualTo("Mount Elbrus")
        assertThat(logs[0].peakAltitude).isEqualTo(5642)
        assertThat(logs[0].note).isEqualTo("windy")

        // De-dup query works.
        assertThat(db.summitLogDao().countRecentForPeak("Mount Elbrus@43.3550,42.4392", 0)).isEqualTo(1)

        db.close()
    }
}
