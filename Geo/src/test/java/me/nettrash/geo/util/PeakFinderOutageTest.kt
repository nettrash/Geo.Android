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
import java.util.Random
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
    private val mirrorBPeak = OverpassStub.Peak("Mirror B Peak", 45.9850, 7.6450, 3000.0)

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

    // ── The merges' duplicate check ─────────────────────────────────

    /** The local merge checks every pack peak (up to 4000 a pack, on every
     *  network search) against everything merged before it through a
     *  latitude-band index, not a scan of the whole set. It must keep exactly
     *  what the 200 m rule, applied pair by pair, keeps: here a pack dense with
     *  near duplicates that straddles the equator, where band numbers change
     *  sign, after a second pack 111 km east that shares its bands. */
    @Test
    fun theLocalMergeKeepsExactlyWhatThe200mRuleKeeps() = runBlocking<Unit> {
        val random = Random(26)
        val origin = location(-0.0005, 37.0)
        val current = random.scatter("Current", 20, origin.latitude, origin.longitude)
        val pack = random.scatter("Far", 300, origin.latitude, origin.longitude + 1.0) +
            random.scatter("Near", 1500, origin.latitude, origin.longitude)

        val result = finder(OverpassStub { null }).search(at = origin, current = current, offline = pack)

        val expected = keptBy200mRule(origin, kept = current, candidates = pack)
        // Dense enough that most of the pack is dropped, and under the 300 cap,
        // so every peak the rule keeps is compared.
        assertThat(expected.size).isAtLeast(100)
        assertThat(expected.size).isLessThan(300)
        assertThat(names(result)).containsExactlyElementsIn(expected).inOrder()
    }

    /** ...and so must the confirmed merge, which checks the bundled mountains,
     *  then every pack peak, against the answer and everything accepted before
     *  it. The answer's own nodes are never checked against each other, so the
     *  rule starts from all of them: here 60 nodes, some within 200 m of
     *  another, then 150 bundled summits and the two packs above, all
     *  straddling latitude bands and the equator. */
    @Test
    fun theConfirmedMergeKeepsExactlyWhatThe200mRuleKeeps() = runBlocking<Unit> {
        val random = Random(25)
        val origin = location(-0.0005, 37.0)
        val answer = random.scatter("OSM", 60, origin.latitude, origin.longitude)
        val known = random.scatter("Bundled", 150, origin.latitude, origin.longitude)
        val pack = random.scatter("Far", 300, origin.latitude, origin.longitude + 1.0) +
            random.scatter("Near", 1500, origin.latitude, origin.longitude)
        val stub = OverpassStub {
            OverpassStub.body(answer.map { OverpassStub.Peak(it.name, it.latitude, it.longitude, it.altitude) })
        }
        val mountains = MountainData(
            highest = MountainList(
                mountains = known.map {
                    MountainInfo(
                        name = it.name,
                        height = it.altitude.toInt(),
                        coordinates = MountainCoordinates(it.latitude, it.longitude)
                    )
                }
            )
        )

        val result = finder(stub).search(at = origin, offline = pack, mountains = mountains)

        val expected = keptBy200mRule(origin, kept = answer, candidates = known + pack)
        val answerNodesNearAnother = answer.count { node ->
            answer.any { it !== node && metres(it.latitude, it.longitude, node) < 200 }
        }
        assertThat(result.stale).isFalse()
        assertThat(answerNodesNearAnother).isAtLeast(2)
        // Dense enough that most of the local peaks are dropped, and under the
        // 300 cap, so every peak the rule keeps is compared.
        assertThat(expected.size).isAtLeast(100)
        assertThat(expected.size).isLessThan(300)
        assertThat(names(result)).containsExactlyElementsIn(expected).inOrder()
    }

    // ── A confirmed answer and the peaks carried over ───────────────

    /** After a failed search the caller shows the local merge, a bundled summit
     *  included, and hands it back as `currentPeaks` (GeoViewModel's `_peaks`).
     *  The next answer brings the OSM node for the same mountain: ~68 m away,
     *  under another name, so another id. Carried over by id, the bundled
     *  summit stood next to it until the TTL dropped it, up to an hour later. A
     *  confirmed answer replaces any carried-over peak within 200 m of one of
     *  its peaks, and the next answer does not bring it back. */
    @Test
    fun aConfirmedAnswerReplacesACarriedOverPeakWithin200m() = runBlocking<Unit> {
        val now = AtomicLong(System.currentTimeMillis())
        val stub = OverpassStub { null }
        val finder = finder(stub, now)
        val summit = MountainData(
            highest = MountainList(
                mountains = listOf(
                    MountainInfo(
                        name = "Bundled Summit",
                        height = 3200,
                        coordinates = MountainCoordinates(45.9900, 7.6650)
                    )
                )
            )
        )
        val node = OverpassStub.Peak("OSM Summit", 45.9905, 7.6655, 3205.0)
        assertThat(GeoCalculations.distanceBetween(45.9900, 7.6650, node.lat, node.lon)).isWithin(1.0).of(68.0)

        val failed = finder.search(mountains = summit)
        assertThat(names(failed)).containsExactly("Bundled Summit")
        assertThat(failed.stale).isTrue()

        stub.answer = { OverpassStub.body(listOf(node)) }
        now.addAndGet(61 * SECOND)       // past the retry backoff
        val confirmed = finder.search(current = failed.peaks, mountains = summit)
        assertThat(names(confirmed)).containsExactly("OSM Summit")
        val asked = stub.hosts.size

        now.addAndGet(16 * MINUTE)       // past the cache age, so asked again
        val later = finder.search(current = confirmed.peaks, mountains = summit)
        assertThat(stub.hosts).hasSize(asked + 1)
        assertThat(names(later)).containsExactly("OSM Summit")
    }

    /** Only within 200 m: a carried-over peak 250 m from every peak of the
     *  answer is another summit, and is carried over as before. */
    @Test
    fun aCarriedOverPeak250mFromEveryAnsweredPeakIsKept() = runBlocking<Unit> {
        val neighbour = NearbyPeak.create("Neighbour", osmPeak.lat + 250.0 / METRES_PER_DEGREE, osmPeak.lon, 3100.0, 0.0, 0.0)
        assertThat(metres(osmPeak.lat, osmPeak.lon, neighbour)).isWithin(0.5).of(250.0)

        val result = finder(OverpassStub { OverpassStub.body(listOf(osmPeak)) }).search(current = listOf(neighbour))

        assertThat(names(result)).containsExactly("OSM Peak", "Neighbour")
    }

    /** And the nodes of one answer are never checked against each other: two
     *  OSM nodes 150 m apart are two summits, shown both, and still both when
     *  the next answer finds them carried over. */
    @Test
    fun twoNodesOfOneAnswer150mApartAreBothKept() = runBlocking<Unit> {
        val now = AtomicLong(System.currentTimeMillis())
        val twin = OverpassStub.Peak("OSM Twin", osmPeak.lat + 150.0 / METRES_PER_DEGREE, osmPeak.lon, 3150.0)
        assertThat(GeoCalculations.distanceBetween(osmPeak.lat, osmPeak.lon, twin.lat, twin.lon)).isWithin(0.5).of(150.0)
        val finder = finder(OverpassStub { OverpassStub.body(listOf(osmPeak, twin)) }, now)

        val first = finder.search()
        assertThat(names(first)).containsExactly("OSM Peak", "OSM Twin")

        now.addAndGet(16 * MINUTE)       // past the cache age, so asked again
        val next = finder.search(current = first.peaks)
        assertThat(names(next)).containsExactly("OSM Peak", "OSM Twin")
    }

    /** [count] peaks at 3000 m, scattered over 0.03° (~3.3 km) around
     *  ([lat], [lon]) and named "[prefix] <i>". */
    private fun Random.scatter(prefix: String, count: Int, lat: Double, lon: Double) = List(count) { i ->
        NearbyPeak.create(
            "$prefix $i",
            lat + (nextDouble() - 0.5) * 0.03,
            lon + (nextDouble() - 0.5) * 0.03,
            3000.0, 0.0, 0.0
        )
    }

    private fun metres(lat: Double, lon: Double, peak: NearbyPeak) =
        GeoCalculations.distanceBetween(lat, lon, peak.latitude, peak.longitude)

    /** The names the 200 m rule, applied pair by pair, keeps: all of [kept],
     *  then each of [candidates] with nothing kept so far within 200 m. In the
     *  order they are shown: out to the 80 km render distance, nearest first. */
    private fun keptBy200mRule(origin: Location, kept: List<NearbyPeak>, candidates: List<NearbyPeak>): List<String> {
        val shown = kept.toMutableList()
        for (peak in candidates) {
            if (shown.none { metres(it.latitude, it.longitude, peak) < 200 }) shown += peak
        }
        return shown
            .filter { metres(origin.latitude, origin.longitude, it) <= 80_000 }
            .sortedBy { metres(origin.latitude, origin.longitude, it) }
            .map { it.name }
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

    // ── A caller that has lost its peaks ────────────────────────────

    /** GeoViewModel lives as long as the activity, this singleton as long as
     *  the process: after Back finishes the activity (Android 9–11) a cached
     *  process hands the next GeoViewModel an empty set while the reuse state
     *  here still says "nothing to ask". It got that empty set back — no peaks,
     *  no label — on every tick for up to 15 min after a confirmed answer. It
     *  gets the peaks it lost, still without a request. */
    @Test
    fun aNewViewModelGetsTheConfirmedPeaksBack() = runBlocking<Unit> {
        val stub = OverpassStub { OverpassStub.body(listOf(osmPeak)) }
        val finder = finder(stub)
        assertThat(names(finder.search())).containsExactly("OSM Peak")

        val newViewModel = finder.search(current = emptyList())
        assertThat(stub.hosts).hasSize(1)    // the reuse path: nothing was asked
        assertThat(names(newViewModel)).containsExactly("OSM Peak")
        assertThat(newViewModel.stale).isFalse()
    }

    /** ...and with no signal, the peaks of a downloaded pack, which stayed
     *  hidden for the whole 60 s retry backoff — with the reason the last
     *  search failed. */
    @Test
    fun aNewViewModelGetsThePackPeaksBackDuringTheBackoff() = runBlocking<Unit> {
        val stub = OverpassStub { null }
        val finder = finder(stub)
        val failed = finder.search(current = listOf(keptPeak), offline = listOf(packPeak), mountains = bundled)
        assertThat(names(failed)).containsExactly("Kept", "Pack Peak", "Bundled Peak")
        val asked = stub.hosts.size

        val newViewModel = finder.search(current = emptyList(), offline = listOf(packPeak), mountains = bundled)
        assertThat(stub.hosts).hasSize(asked)    // still inside the backoff
        assertThat(names(newViewModel)).containsExactly("Kept", "Pack Peak", "Bundled Peak")
        assertThat(newViewModel.error).isEqualTo(failed.error)
    }

    /** ...and a tick that lands while a search is still failing over (the old
     *  GeoViewModel's, running out its blocking request) gets what that search
     *  already published. */
    @Test
    fun aNewViewModelGetsThePeaksOfASearchInFlight() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val finder = finder(OverpassStub { release.await(10, TimeUnit.SECONDS); null })
        val early = CompletableDeferred<PeakFinder.PeakSearchResult>()
        val search = async(Dispatchers.IO) {
            finder.search(offline = listOf(packPeak), mountains = bundled, onLocal = { early.complete(it) })
        }
        val published = withTimeoutOrNull(5_000) { early.await() }
        val tick = finder.search(current = emptyList(), offline = listOf(packPeak), mountains = bundled)
        val stillInFlight = !search.isCompleted
        release.countDown()
        search.await()

        assertThat(published).isNotNull()
        assertThat(stillInFlight).isTrue()
        assertThat(names(tick)).containsExactly("Pack Peak", "Bundled Peak")
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

    // ── Overpass runtime errors ─────────────────────────────────────

    /** Overpass reports a query that ran out of memory — or timed out on an
     *  overloaded server — as HTTP 200 with an empty `elements` and the reason
     *  in `remark` (seen live on overpass.openstreetmap.fr). That is the server
     *  failing to answer, not "no peaks here": the next mirror is asked. */
    @Test
    fun aRuntimeErrorRemarkFailsOverToTheNextMirror() = runBlocking<Unit> {
        val stub = OverpassStub { host ->
            when (host) {
                "overpass-api.de" -> OverpassStub.body(emptyList(), remark = OUT_OF_MEMORY)
                "overpass.private.coffee" -> OverpassStub.body(listOf(mirrorBPeak))
                else -> null
            }
        }
        val result = finder(stub).search()

        assertThat(names(result)).containsExactly("Mirror B Peak")
        assertThat(result.stale).isFalse()
        assertThat(stub.hosts).containsExactly("overpass-api.de", "overpass.private.coffee").inOrder()
    }

    /** Every mirror overloaded is a failed search — the peaks on screen stay,
     *  marked stale — never a confirmed "none", which cleared the label and the
     *  error, was reused for 15 min with no retry (so the peaks it never
     *  re-confirmed aged out) and rewrote the snapshot. The reason is the remark
     *  itself, trimmed to 120 characters, and the match ignores case. */
    @Test
    fun runtimeErrorsOnEveryMirrorAreAFailureNotNoPeaks() = runBlocking<Unit> {
        val remark = "Runtime error: Query timed out in \"query\" at line 2 after 10 seconds. " + "x".repeat(200)
        val stub = OverpassStub { OverpassStub.body(emptyList(), remark = "  $remark\n") }
        val result = finder(stub).search(current = listOf(keptPeak))

        assertThat(stub.hosts).hasSize(PeakFinder.overpassEndpoints.size)
        assertThat(names(result)).containsExactly("Kept")
        assertThat(result.stale).isTrue()
        assertThat(result.error).contains("overpass-api.de: ${remark.take(120)};")
        assertThat(File(ctx.cacheDir, snapshotName).exists()).isFalse()
    }

    // ── What counts as an Overpass answer ───────────────────────────

    /** A 200 JSON body without `elements` (a proxy's, say) is not an Overpass
     *  answer. With `elements` defaulting to empty it decoded as a confirmed
     *  "no peaks here": reused for 15 min, the snapshot rewritten, a 0-peak
     *  pack saved. It is rejected, as on iOS, and the next mirror is asked. */
    @Test
    fun aBodyWithoutElementsFailsOverToTheNextMirror() = runBlocking<Unit> {
        val stub = OverpassStub { host ->
            when (host) {
                "overpass-api.de" -> """{"version":0.6,"generator":"stub","osm3s":{}}"""
                "overpass.private.coffee" -> OverpassStub.body(listOf(mirrorBPeak))
                else -> null
            }
        }
        val result = finder(stub).search()

        assertThat(names(result)).containsExactly("Mirror B Peak")
        assertThat(result.stale).isFalse()
        assertThat(stub.hosts).containsExactly("overpass-api.de", "overpass.private.coffee").inOrder()
    }

    /** Only HTTP 200 is an answer, as on iOS: any other 2xx, a transforming
     *  proxy's 203 say, is rejected with its code, even when the body reads
     *  like an empty answer. Here every mirror does it, so the search fails:
     *  the peaks on screen stay, marked stale, and nothing is written. */
    @Test
    fun aSuccessCodeOtherThan200IsNotAnAnswer() = runBlocking<Unit> {
        val stub = OverpassStub { OverpassStub.body(emptyList()) }
        stub.status = { 203 }
        val result = finder(stub).search(current = listOf(keptPeak))

        assertThat(stub.hosts).hasSize(PeakFinder.overpassEndpoints.size)
        assertThat(names(result)).containsExactly("Kept")
        assertThat(result.stale).isTrue()
        assertThat(result.error).isEqualTo(
            "overpass-api.de: HTTP 203; overpass.private.coffee: HTTP 203; overpass.openstreetmap.fr: HTTP 203"
        )
        assertThat(File(ctx.cacheDir, snapshotName).exists()).isFalse()
    }

    /** Only a runtime error is a failure; any other remark, or none, is an
     *  answer as before. */
    @Test
    fun anyOtherRemarkIsStillAnAnswer() = runBlocking<Unit> {
        val stub = OverpassStub {
            OverpassStub.body(listOf(osmPeak), remark = "runtime remark: Timeout is 10 and maxsize is 536870912.")
        }
        val result = finder(stub).search()

        assertThat(names(result)).containsExactly("OSM Peak")
        assertThat(result.stale).isFalse()
        assertThat(stub.hosts).hasSize(1)
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

    /** "possibly outdated" is about the restored peaks that will be shown, not
     *  about what the file holds. A snapshot whose only peak lies beyond the
     *  80 km render distance shows nothing here, yet it labelled the bundled
     *  summit next to it "possibly outdated" for the whole failover. As on
     *  iOS, which judges the pruned set. */
    @Test
    fun aRestoredSnapshotWithNothingInRangeIsNotPossiblyOutdated() = runBlocking<Unit> {
        // Answered ~45 km north with no OSM peaks, under a pack whose one peak
        // is ~72 km further north: the snapshot holds just that peak, ~117 km
        // from here, and its centre is close enough to be restored here.
        val north = location(here.latitude + 0.4, here.longitude)
        val farNorth = NearbyPeak.create("Far North", here.latitude + 1.05, here.longitude, 4000.0, 0.0, 0.0)
        finder(OverpassStub { OverpassStub.body(emptyList()) }).search(at = north, offline = listOf(farNorth))
        val entries = Json.parseToJsonElement(File(ctx.cacheDir, snapshotName).readText())
            .jsonObject.getValue("entries").jsonArray
        assertThat(entries.map { it.jsonObject.getValue("name").jsonPrimitive.content }).containsExactly("Far North")
        assertThat(metres(here.latitude, here.longitude, farNorth)).isGreaterThan(80_000.0)

        // A restart here with no pack, a bundled summit ~17 km away, and the
        // first mirror holding the request.
        val release = CountDownLatch(1)
        val restarted = finder(OverpassStub { release.await(10, TimeUnit.SECONDS); null })
        val early = CompletableDeferred<PeakFinder.PeakSearchResult>()
        val search = async(Dispatchers.IO) {
            restarted.search(mountains = bundled, onLocal = { early.complete(it) })
        }
        val published = withTimeoutOrNull(5_000) { early.await() }
        val networkStillPending = !search.isCompleted
        release.countDown()
        search.await()

        assertThat(published).isNotNull()
        assertThat(networkStillPending).isTrue()
        assertThat(names(published!!)).containsExactly("Bundled Peak")
        assertThat(published.stale).isFalse()
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

        /** One degree of latitude on the sphere [GeoCalculations] measures on. */
        const val METRES_PER_DEGREE = GeoCalculations.EARTH_RADIUS * Math.PI / 180.0

        /** Verbatim from overpass.openstreetmap.fr, answered with HTTP 200. */
        const val OUT_OF_MEMORY = "runtime error: Query ran out of memory in \"query\" at line 1. " +
            "It would need at least 2 MB of RAM to continue."
    }
}
