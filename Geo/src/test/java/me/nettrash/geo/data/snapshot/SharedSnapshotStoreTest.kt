package me.nettrash.geo.data.snapshot

import android.content.Context
import com.google.common.truth.Truth.assertThat
import me.nettrash.geo.data.model.InformationToken
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedSnapshotStoreTest {

    private val ctx: Context by lazy { RuntimeEnvironment.getApplication() }

    @Before
    fun resetStore() {
        SharedSnapshotStore.clearBuffer(ctx)
        // Clear current by writing an explicit zero token then clear.
        ctx.getSharedPreferences("me.nettrash.geo.snapshot", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @After
    fun cleanup() = resetStore()

    @Test
    fun `writes and reads current snapshot`() {
        val token = sample(recordDate = 1_700_000_000_000L, pressureKpa = 90.0)
        SharedSnapshotStore.write(ctx, token)

        val read = SharedSnapshotStore.readCurrent(ctx)

        assertThat(read).isEqualTo(token)
    }

    @Test
    fun `readCurrent returns null when nothing has been written`() {
        assertThat(SharedSnapshotStore.readCurrent(ctx)).isNull()
    }

    @Test
    fun `buffer holds at most BUFFER_CAPACITY entries`() {
        repeat(SharedSnapshotStore.BUFFER_CAPACITY + 5) { i ->
            SharedSnapshotStore.write(
                ctx,
                sample(
                    recordDate = 1_700_000_000_000L + i * 60_000L,
                    pressureKpa = 90.0 + i // distinct enough to defeat dedup
                )
            )
        }

        val buffer = SharedSnapshotStore.readBuffer(ctx)

        assertThat(buffer).hasSize(SharedSnapshotStore.BUFFER_CAPACITY)
        // Oldest entries dropped first → first entry should match
        // index 5 (we wrote +5 over the capacity).
        assertThat(buffer.first().barPressure).isEqualTo(95.0)
    }

    @Test
    fun `buffer dedups near-duplicate writes`() {
        val base = 1_700_000_000_000L
        SharedSnapshotStore.write(ctx, sample(recordDate = base, pressureKpa = 90.0))
        // 1 second later, identical pressure → should be skipped.
        SharedSnapshotStore.write(ctx, sample(recordDate = base + 1_000L, pressureKpa = 90.0))
        // 10 seconds later, also identical → time gap > 5 s so kept.
        SharedSnapshotStore.write(ctx, sample(recordDate = base + 10_000L, pressureKpa = 90.0))

        val buffer = SharedSnapshotStore.readBuffer(ctx)

        assertThat(buffer).hasSize(2)
        assertThat(buffer.last().recordDate).isEqualTo(base + 10_000L)
    }

    @Test
    fun `clearBuffer empties without touching current`() {
        val token = sample(recordDate = 1L, pressureKpa = 100.0)
        SharedSnapshotStore.write(ctx, token)
        assertThat(SharedSnapshotStore.readBuffer(ctx)).isNotEmpty()

        SharedSnapshotStore.clearBuffer(ctx)

        assertThat(SharedSnapshotStore.readBuffer(ctx)).isEmpty()
        assertThat(SharedSnapshotStore.readCurrent(ctx)).isEqualTo(token)
    }

    @Test
    fun `legacy tokens without lat-lon decode cleanly`() {
        // Write a "legacy" JSON blob missing the lat/lon fields.
        ctx.getSharedPreferences("me.nettrash.geo.snapshot", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(
                "current",
                """{"recordDate":42,"gpsAltitude":1.0,"gpsSpeed":2.0,"barPressure":90.0,"barAltitude":500.0}"""
            )
            .apply()

        val read = SharedSnapshotStore.readCurrent(ctx)

        assertThat(read).isNotNull()
        assertThat(read!!.gpsLatitude).isEqualTo(0.0)
        assertThat(read.gpsLongitude).isEqualTo(0.0)
        assertThat(read.barPressure).isEqualTo(90.0)
    }

    private fun sample(
        recordDate: Long,
        pressureKpa: Double = 101.0
    ) = InformationToken(
        recordDate = recordDate,
        gpsAltitude = 100.0,
        gpsSpeed = 0.0,
        barPressure = pressureKpa,
        barAltitude = 200.0,
        gpsLatitude = 1.23,
        gpsLongitude = 4.56
    )
}
