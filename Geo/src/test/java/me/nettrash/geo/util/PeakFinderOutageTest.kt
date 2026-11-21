package me.nettrash.geo.util

import android.content.Context
import android.location.Location
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nettrash.geo.ar.TerrainElevationService
import me.nettrash.geo.data.model.MountainCoordinates
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.MountainInfo
import me.nettrash.geo.data.model.MountainList
import me.nettrash.geo.data.model.NearbyPeak
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * What the Nature tab shows when the peak servers let it down (#26), driven end
 * to end through [PeakFinder.searchPeaks] with no network: [OverpassStub]
 * answers by host, [PeakFinder.clock] stands in for the hours an outage lasts,
 * and every stubbed peak carries `ele`, so nothing reaches the DEM either.
 *
 * Each test is one way an outage used to read as "there are no peaks here", or
 * a promise the fix has to keep. The pure reuse rule and the mirror list live in
 * [PeakFinderResilienceTest]; this file needs Robolectric because the search
 * takes an `android.location.Location`. Mirrors iOS `PeakFinderResilienceTests`.
 */
@RunWith(RobolectricTestRunner::class)
class PeakFinderOutageTest {

    private val ctx: Context by lazy { RuntimeEnvironment.getApplication() }

    private val observerAlt = 2000.0

    /** Above Zermatt. More than two decimals on purpose, so the rounding of the
     *  stored snapshot centre shows. */
    private val here = location(45.9763, 7.6586)

    /** What a mirror answers: a summit ~1.6 km away, inside the live 5 km radius. */
    private val osmPeak = OverpassStub.Peak("OSM Peak", 45.9900, 7.6650, 3200.0)
    private val freshPeak = OverpassStub.Peak("Fresh Peak", 45.9700, 7.6800, 3100.0)

    /** A downloaded offline-pack peak ~4 km away, and one ~100 km away — beyond
     *  the 80 km render distance, so not "a pack for here". */
    private val packPeak = NearbyPeak.create("Pack Peak", 46.0050, 7.6900, 3500.0, 0.0, 0.0)
    private val farPackPeak = NearbyPeak.create("Far Pack Peak", 46.8800, 7.6586, 3000.0, 0.0, 0.0)

    /** A peak already on screen from an earlier search, ~2.2 km away. */
    private val keptPeak = NearbyPeak.create("Kept", 45.9600, 7.6400, 2900.0, 0.0, 0.0)

    /** The bundled mountain list, with one summit ~17 km away. */
    private val bundled = MountainData(
        highest = MountainList(
            mountains = listOf(
                MountainInfo(
                    name = "Bundled Peak",
                    height = 4000,
                    coordinates = MountainCoordinates(46.1000, 7.8000)
                )
            )
        )
    )

    private val snapshotName = "nearby_peaks_cache.json"

    /** A snapshot left by an earlier test would be restored by the next fresh
     *  finder, so it goes either side of every test — from both the cache dir
     *  and the files dir it used to live in. */
    @Before
    fun clean() = removeSnapshots()

    @After
    fun cleanUp() = removeSnapshots()

    private fun removeSnapshots() {
        File(ctx.cacheDir, snapshotName).delete()
        File(ctx.filesDir, snapshotName).delete()
    }

    private fun location(lat: Double, lon: Double) = Location("test").apply {
        latitude = lat
        longitude = lon
        altitude = observerAlt
    }

    /** A fresh finder — what a cold start gets — whose only network is [stub]
     *  and, given [now], whose clock is that. */
    private fun finder(stub: OverpassStub, now: AtomicLong? = null): PeakFinder =
        PeakFinder(TerrainElevationService(ctx), ctx).apply {
            client = stub.client()
            if (now != null) clock = { now.get() }
        }

    private suspend fun PeakFinder.search(
        at: Location = here,
        current: List<NearbyPeak> = emptyList(),
        offline: List<NearbyPeak> = emptyList(),
        mountains: MountainData? = null,
        onLocal: (PeakFinder.PeakSearchResult) -> Unit = {}
    ): PeakFinder.PeakSearchResult = searchPeaks(
        at, mountains, current, offline,
        observerAltitude = observerAlt,
        onLocalPeaks = onLocal
    )

    private fun names(result: PeakFinder.PeakSearchResult) = result.peaks.map { it.name }

    // ── Local peaks are always shown ────────────────────────────────

    /** The 1.2 promise: a downloaded pack names the peaks with NO signal — and
     *  so do the bundled mountains, which never needed the network. The failure
     *  branch used to return before either was merged, so a phone with no signal
     *  and a pack for this very valley showed 0 peaks. */
    @Test
    fun packAndBundledPeaksAreShownWithNoSignal() = runBlocking<Unit> {
        val stub = OverpassStub { null }
        val result = finder(stub).search(offline = listOf(packPeak), mountains = bundled)

        assertThat(stub.hosts).hasSize(PeakFinder.overpassEndpoints.size)
        assertThat(names(result)).containsExactly("Pack Peak", "Bundled Peak")
    }

    /** ...and they must not wait for the network: a failover across every
     *  mirror can take ~25 s, and these peaks are on the phone already. The stub
     *  holds the request until the test has seen (or given up on) the early
     *  publish. */
    @Test
    fun localPeaksArePublishedBeforeTheNetworkAnswers() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val finder = finder(OverpassStub { release.await(10, TimeUnit.SECONDS); null })
        val early = CompletableDeferred<PeakFinder.PeakSearchResult>()

        val search = async(Dispatchers.IO) {
            finder.search(offline = listOf(packPeak), mountains = bundled, onLocal = { early.complete(it) })
        }
        val published = withTimeoutOrNull(5_000) { early.await() }
        val networkStillPending = !search.isCompleted
        release.countDown()
        val afterFailure = search.await()

        assertThat(published).isNotNull()
        assertThat(networkStillPending).isTrue()
        assertThat(names(published!!)).containsExactly("Pack Peak", "Bundled Peak")
        assertThat(names(afterFailure)).containsExactly("Pack Peak", "Bundled Peak")
    }

    // ── No TTL expiry during an outage ──────────────────────────────

    /** `peakTTL` (1 h) used to prune on every path, so an hour into an outage
     *  the carried-over peaks — and a snapshot restored from disk — simply
     *  vanished. It now cuts only right after a confirmed answer. */
    @Test
    fun carriedOverPeaksOutliveAnOutageLongerThanTheTtl() = runBlocking<Unit> {
        val now = AtomicLong(System.currentTimeMillis())
        val stub = OverpassStub { OverpassStub.body(listOf(osmPeak)) }
        val finder = finder(stub, now)

        val confirmed = finder.search()
        assertThat(names(confirmed)).containsExactly("OSM Peak")

        stub.answer = { null }
        now.addAndGet(61 * MINUTE)       // past the 15 min cache age AND the 1 h TTL
        val anHourIn = finder.search(current = confirmed.peaks)
        assertThat(names(anHourIn)).containsExactly("OSM Peak")
        assertThat(anHourIn.stale).isTrue()

        now.addAndGet(60 * MINUTE)       // two hours in, well past the retry backoff
        val twoHoursIn = finder.search(current = anHourIn.peaks)
        assertThat(names(twoHoursIn)).containsExactly("OSM Peak")

        // The outage ends, and the confirmed answer is what ages out the peak it
        // no longer contains.
        stub.answer = { OverpassStub.body(listOf(freshPeak)) }
        now.addAndGet(2 * MINUTE)
        val recovered = finder.search(current = twoHoursIn.peaks)
        assertThat(names(recovered)).containsExactly("Fresh Peak")
        assertThat(recovered.stale).isFalse()
    }

    // ── The stale flag and the error ────────────────────────────────

    /** "possibly outdated" describes the peaks, not the last request: the reuse
     *  tick after a failure confirms nothing, so it must clear neither the label
     *  nor the reason. Both used to reset on the very next 5 s tick. */
    @Test
    fun staleFlagAndErrorSurviveTheReuseTick() = runBlocking<Unit> {
        val stub = OverpassStub { null }
        val finder = finder(stub)

        val failed = finder.search(current = listOf(keptPeak))
        assertThat(failed.stale).isTrue()
        assertThat(failed.error).isNotNull()
        val asked = stub.hosts.size

        val tick = finder.search(current = failed.peaks)
        assertThat(stub.hosts).hasSize(asked)    // the reuse path: nothing was asked
        assertThat(names(tick)).containsExactly("Kept")
        assertThat(tick.stale).isTrue()
        assertThat(tick.error).isEqualTo(failed.error)
    }

    /** Nor does a tick that lands while a retry is still failing over. That path
     *  reported `lastSuccessAt == null`, so after a success and then a failure
     *  the label blinked off for the length of every retry. */
    @Test
    fun aTickDuringARetryKeepsTheFlagAndError() = runBlocking<Unit> {
        val now = AtomicLong(System.currentTimeMillis())
        val stub = OverpassStub { OverpassStub.body(listOf(osmPeak)) }
        val finder = finder(stub, now)
        val confirmed = finder.search()

        stub.answer = { null }
        now.addAndGet(16 * MINUTE)       // the confirmed answer is too old to reuse
        val failed = finder.search(current = confirmed.peaks)
        assertThat(failed.stale).isTrue()

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        stub.answer = { entered.countDown(); release.await(10, TimeUnit.SECONDS); null }
        now.addAndGet(61 * SECOND)       // the backoff is over, so a retry starts...
        val retry = async(Dispatchers.IO) { finder.search(current = failed.peaks) }
        val retryInFlight = entered.await(5, TimeUnit.SECONDS)
        val tick = finder.search(current = failed.peaks)   // ...and a tick lands mid-failover
        release.countDown()
        retry.await()

        assertThat(retryInFlight).isTrue()
        assertThat(names(tick)).containsExactly("OSM Peak")
        assertThat(tick.stale).isTrue()
        assertThat(tick.error).isEqualTo(failed.error)
    }

    /** A downloaded pack is the explicit offline answer: where one covers here,
     *  its peaks are what the user asked for, not "possibly outdated" — which
     *  keeps the no-signal summit exactly as it was in 1.3. A pack for somewhere
     *  beyond the render distance is no answer for here. */
    @Test
    fun aPackCoveringHereIsNeverPossiblyOutdated() = runBlocking<Unit> {
        val finder = finder(OverpassStub { null })

        val failed = finder.search(current = listOf(keptPeak), offline = listOf(packPeak))
        assertThat(names(failed)).contains("Kept")
        assertThat(failed.stale).isFalse()

        val tick = finder.search(current = failed.peaks, offline = listOf(packPeak))
        assertThat(tick.stale).isFalse()

        val elsewhere = finder(OverpassStub { null })
            .search(current = listOf(keptPeak), offline = listOf(farPackPeak))
        assertThat(names(elsewhere)).containsExactly("Kept")
        assertThat(elsewhere.stale).isTrue()
    }

    /** ...and a confirmed answer clears both, whatever came before. */
    @Test
    fun aConfirmedAnswerClearsTheFlagAndError() = runBlocking<Unit> {
        val now = AtomicLong(System.currentTimeMillis())
        val stub = OverpassStub { null }
        val finder = finder(stub, now)
        val failed = finder.search(current = listOf(keptPeak))
        assertThat(failed.stale).isTrue()

        stub.answer = { OverpassStub.body(listOf(osmPeak)) }
        now.addAndGet(61 * SECOND)       // past the retry backoff
        val confirmed = finder.search(current = failed.peaks)
        assertThat(names(confirmed)).containsExactly("OSM Peak", "Kept")
        assertThat(confirmed.stale).isFalse()
        assertThat(confirmed.error).isNull()
    }

    // ── The on-disk snapshot ────────────────────────────────────────

    /** The snapshot is derived data: it lives in the cache dir, which Auto
     *  Backup excludes, and keeps the point it was searched from only to ~1 km
     *  (2 decimal places) — all the 50 km "is this for here?" check needs. The
     *  peaks keep their exact coordinates. */
    @Test
    fun theSnapshotLivesInTheCacheDirWithItsCentreRounded() = runBlocking<Unit> {
        finder(OverpassStub { OverpassStub.body(listOf(osmPeak)) }).search()

        val cached = File(ctx.cacheDir, snapshotName)
        assertThat(cached.exists()).isTrue()
        assertThat(File(ctx.filesDir, snapshotName).exists()).isFalse()
        val snapshot = Json.parseToJsonElement(cached.readText()).jsonObject
        assertThat(snapshot.getValue("centerLat").jsonPrimitive.double).isEqualTo(45.98)
        assertThat(snapshot.getValue("centerLon").jsonPrimitive.double).isEqualTo(7.66)
        val entry = snapshot.getValue("entries").jsonArray.single().jsonObject
        assertThat(entry.getValue("latitude").jsonPrimitive.double).isEqualTo(osmPeak.lat)
        assertThat(entry.getValue("longitude").jsonPrimitive.double).isEqualTo(osmPeak.lon)
    }

    /** After a restart the last good set is the baseline — and it must show at
     *  once, through the early publish, with real distances and bearings (the
     *  overlay fades and scales by distance), not after a failover that can take
     *  ~25 s. It is "possibly outdated" until something confirms it. */
    @Test
    fun aRestoredSnapshotIsShownAtOnceWithRealGeometry() = runBlocking<Unit> {
        finder(OverpassStub { OverpassStub.body(listOf(osmPeak)) }).search()

        // A fresh finder is a restarted app: ~1 km east, and no mirror answers.
        val moved = location(45.9763, 7.6716)
        val release = CountDownLatch(1)
        val restarted = finder(OverpassStub { release.await(10, TimeUnit.SECONDS); null })
        val early = CompletableDeferred<PeakFinder.PeakSearchResult>()
        val search = async(Dispatchers.IO) {
            restarted.search(at = moved, onLocal = { early.complete(it) })
        }
        val published = withTimeoutOrNull(5_000) { early.await() }
        release.countDown()
        val afterFailure = search.await()

        assertThat(published).isNotNull()
        val peak = published!!.peaks.single()
        assertThat(peak.name).isEqualTo("OSM Peak")
        assertThat(peak.distance).isWithin(1.0).of(
            GeoCalculations.distanceBetween(moved.latitude, moved.longitude, osmPeak.lat, osmPeak.lon)
        )
        assertThat(peak.bearing).isWithin(0.01).of(
            GeoCalculations.bearing(moved.latitude, moved.longitude, osmPeak.lat, osmPeak.lon)
        )
        assertThat(published.stale).isTrue()
        assertThat(names(afterFailure)).containsExactly("OSM Peak")
        assertThat(afterFailure.stale).isTrue()
    }

    /** Restored where a downloaded pack covers here: the pack is the offline
     *  answer, so nothing is "possibly outdated" — before the failure or after. */
    @Test
    fun aRestoredSnapshotIsNotOutdatedWhereAPackCoversHere() = runBlocking<Unit> {
        finder(OverpassStub { OverpassStub.body(listOf(osmPeak)) }).search()

        val release = CountDownLatch(1)
        val restarted = finder(OverpassStub { release.await(10, TimeUnit.SECONDS); null })
        val early = CompletableDeferred<PeakFinder.PeakSearchResult>()
        val search = async(Dispatchers.IO) {
            restarted.search(offline = listOf(packPeak), onLocal = { early.complete(it) })
        }
        val published = withTimeoutOrNull(5_000) { early.await() }
        release.countDown()
        val afterFailure = search.await()

        assertThat(published).isNotNull()
        assertThat(published!!.stale).isFalse()
        assertThat(names(afterFailure)).containsExactly("OSM Peak", "Pack Peak")
        assertThat(afterFailure.stale).isFalse()
    }

    // ── Backoff ─────────────────────────────────────────────────────

    /** The 60 s retry backoff runs from when the failure ARRIVED. Stamped before
     *  the request, a ~30 s failover ate half of it. */
    @Test
    fun theRetryBackoffRunsFromWhenTheFailureArrived() = runBlocking<Unit> {
        val start = System.currentTimeMillis()
        val now = AtomicLong(start)
        // Each mirror takes 10 s to fail, so the failover ends 30 s in.
        val stub = OverpassStub { now.addAndGet(10 * SECOND); null }
        val finder = finder(stub, now)

        finder.search()
        assertThat(now.get() - start).isEqualTo(30 * SECOND)
        val asked = stub.hosts.size

        now.set(start + 75 * SECOND)     // 75 s after it started, 45 s after it failed
        finder.search()
        assertThat(stub.hosts).hasSize(asked)

        now.set(start + 95 * SECOND)     // 65 s after it failed: the backoff is over
        finder.search()
        assertThat(stub.hosts.size).isGreaterThan(asked)
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60_000L
    }
}
