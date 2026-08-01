package me.nettrash.geo.util

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.Calendar
import java.util.TimeZone

/**
 * The SWPC parse contract and the on-disk cache round-trip. [GeomagneticTest]
 * covers the pure core and hands these two subjects here, to the file that
 * owns the wire format.
 *
 * Every numbered rule below is a way this integration fails SILENTLY — no
 * crash, no error, just a card that is confidently wrong about which evening
 * it is describing — so each one is a named test rather than a line inside a
 * bigger one. The numbering matches the comments in [SpaceWeatherRepository]
 * itself, and iOS `SpaceWeatherServiceTests` is this file test-for-test.
 *
 * Nothing here touches the network: the parse is pure, the request is
 * asserted as a value through [SpaceWeatherRepository.forecastRequest], and
 * the cache rules run through [SpaceWeatherStore]'s own directory seam
 * against a scratch folder.
 */
class SpaceWeatherRepositoryTest {

    /** Fixed clock, the same instant the Geomagnetic fixtures use
     *  (1_700_000_000_000 — 2023-11-14T22:13:20Z). */
    private val fetchedAtMs = 1_700_000_000_000L

    /** UTC, always injected — a test that reads the device's calendar
     *  passes in Cupertino and fails in Auckland. */
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun hours(h: Double): Long = (h * 3_600_000.0).toLong()

    /** A scratch directory for the cache tests. JUnit builds a fresh
     *  instance per test, so each one gets its own and [removeIt] takes it
     *  away however the test leaves. */
    private val directory: File =
        Files.createTempDirectory("SpaceWeatherRepositoryTest").toFile()

    @After
    fun removeIt() {
        directory.deleteRecursively()
    }

    // ── Wire fixtures ───────────────────────────────────────────────

    /** The live shape of the product, verified against the endpoint: an
     *  array of objects, `noaa_scale` null, three bins carrying all three
     *  provenance tags, oldest first. */
    private val objectRows = """
        [{"time_tag":"2026-07-18T00:00:00","kp":1.67,"observed":"observed","noaa_scale":null},
         {"time_tag":"2026-07-18T03:00:00","kp":2.33,"observed":"estimated","noaa_scale":null},
         {"time_tag":"2026-07-18T06:00:00","kp":5.00,"observed":"predicted","noaa_scale":null}]
    """.trimIndent()

    /** The very same three bins, emitted in a different order. */
    private val shuffledObjectRows = """
        [{"time_tag":"2026-07-18T06:00:00","kp":5.00,"observed":"predicted","noaa_scale":null},
         {"time_tag":"2026-07-18T00:00:00","kp":1.67,"observed":"observed","noaa_scale":null},
         {"time_tag":"2026-07-18T03:00:00","kp":2.33,"observed":"estimated","noaa_scale":null}]
    """.trimIndent()

    /** The same three bins in the legacy header-row form, with the
     *  separator written as a space, milliseconds attached and Kp quoted —
     *  which is how the sibling products under `/products/` still emit it. */
    private val legacyRows = """
        [["time_tag","Kp","observed","noaa_scale"],
         ["2026-07-18 00:00:00.000","1.67","observed",null],
         ["2026-07-18 03:00:00.000","2.33","estimated",null],
         ["2026-07-18 06:00:00.000","5.00","predicted",null]]
    """.trimIndent()

    private fun parse(body: String): KpSeries? =
        SpaceWeatherRepository.parseKpForecast(body, fetchedAtMs)

    // ── 1. The provenance field is named `observed` ─────────────────

    @Test
    fun provenanceComesFromTheObservedField() {
        // 1. SWPC calls it `observed`, not `provenance`, and its values are
        //    the exact strings the enum maps from, so they go straight
        //    through.
        val series = parse(objectRows)
        assertNotNull(series)
        assertEquals(fetchedAtMs, series!!.fetchedAtMs)
        assertEquals(
            listOf(KpProvenance.OBSERVED, KpProvenance.ESTIMATED, KpProvenance.PREDICTED),
            series.points.map { it.provenance }
        )
        assertEquals(listOf(1.67, 2.33, 5.00), series.points.map { it.kp })
    }

    @Test
    fun aMislabelledProvenanceFieldFallsBackToForecast() {
        // A row that names the field `provenance` is a field we no longer
        // recognise. Calling the number *observed* on that basis would be a
        // claim we cannot support, so it degrades to PREDICTED — which the
        // card captions "forecast".
        val mislabelled =
            parse("""[{"time_tag":"2026-07-18T00:00:00","kp":1.67,"provenance":"observed"}]""")
        assertNotNull(mislabelled)
        assertEquals(KpProvenance.PREDICTED, mislabelled!!.points.first().provenance)

        // An unrecognised value in the right field degrades the same way.
        val unknown =
            parse("""[{"time_tag":"2026-07-18T00:00:00","kp":1.67,"observed":"provisional"}]""")
        assertNotNull(unknown)
        assertEquals(KpProvenance.PREDICTED, unknown!!.points.first().provenance)
    }

    // ── 2. Always sort by time_tag, never by array position ─────────

    @Test
    fun shuffledInputProducesAnIdenticalSeries() {
        // 2. SWPC's ordering is inconsistent across products — `/products/*`
        //    oldest first, `alerts.json` and `json/rtsw/*` newest first — so
        //    the same rows in a different order must decode to the same
        //    series. Equal as whole values, not merely "the same set": if
        //    anything downstream ever indexed by position this fails here
        //    first.
        val ordered = parse(objectRows)
        val shuffled = parse(shuffledObjectRows)
        assertNotNull(ordered)
        assertNotNull(shuffled)
        assertEquals(ordered, shuffled)
        assertEquals(
            shuffled!!.points.map { it.timeMs }.sorted(),
            shuffled.points.map { it.timeMs }
        )
        assertEquals(KpProvenance.OBSERVED, shuffled.points.first().provenance)
        assertEquals(KpProvenance.PREDICTED, shuffled.points.last().provenance)
    }

    // ── 3. time_tag has no zone suffix and IS UTC ───────────────────

    @Test
    fun timeTagIsUtcWhateverTheDeviceZone() {
        // 3. Read in the device's own zone this is silently wrong by up to
        //    14 hours and nothing on the card looks broken while it happens
        //    — the bins simply describe the wrong evening. The device is
        //    forced to UTC+13 for the duration and restored in `finally`, so
        //    the override is undone however this test leaves.
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+13:00"))

            val parsed = SpaceWeatherRepository.parseSwpcTimeTagMs("2026-07-25T15:00:00")
            assertNotNull(parsed)
            val parsedMs = parsed!!
            val parts = Calendar.getInstance(utc).apply { timeInMillis = parsedMs }
            assertEquals(2026, parts.get(Calendar.YEAR))
            assertEquals(Calendar.JULY, parts.get(Calendar.MONTH))
            assertEquals(25, parts.get(Calendar.DAY_OF_MONTH))
            assertEquals(15, parts.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, parts.get(Calendar.MINUTE))
            assertEquals(0, parts.get(Calendar.SECOND))

            // The override really is in effect: the same wall-clock reading
            // in the device zone is a different instant, which is exactly
            // the 13 hours a parse that fell back to the default zone would
            // have lost here.
            val asLocal = Calendar.getInstance(TimeZone.getDefault()).apply {
                clear()
                set(2026, Calendar.JULY, 25, 15, 0, 0)
            }.timeInMillis
            assertNotEquals(asLocal, parsedMs)
            assertEquals(hours(13.0), parsedMs - asLocal)

            // And the whole-product path agrees with the single-tag path.
            val series = parse(
                """[{"time_tag":"2026-07-25T15:00:00","kp":1.67,"observed":"observed","noaa_scale":null}]"""
            )
            assertNotNull(series)
            assertEquals(parsedMs, series!!.points.first().timeMs)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    // ── 4. Dual-format parse ────────────────────────────────────────

    @Test
    fun legacyHeaderRowFormDecodesToTheSameSeries() {
        // 4. Objects first, then the header-row array-of-arrays. Both forms
        //    of the same three bins must produce the same series, including
        //    the separator and millisecond normalisation the legacy rows
        //    need: `LocalDateTime.parse` refuses a space separator outright,
        //    so without it every legacy row is skipped and this form decodes
        //    to ZERO bins — a well-formed "nothing to say" that keeps the
        //    old cache and never looks broken.
        val modern = parse(objectRows)
        val legacy = parse(legacyRows)
        assertNotNull(modern)
        assertNotNull(legacy)
        assertEquals(modern, legacy)
        assertEquals(3, legacy!!.points.size)
    }

    @Test
    fun legacyFormWithoutTheColumnsWeNeedReturnsNull() {
        // A header row we cannot read is a format we do not understand, not
        // an empty product: null, so the caller keeps the cache.
        assertNull(
            parse(
                """
                [["time_tag","planetary_k_index"],
                 ["2026-07-18 00:00:00.000","1.67"]]
                """.trimIndent()
            )
        )
    }

    // ── 5. noaa_scale is ignored entirely ───────────────────────────

    @Test
    fun noaaScaleIsIgnoredWhetherNullOrPopulated() {
        // 5. It is null in every observed row, and its storm-time content is
        //    unverified. G is derived locally from Kp by the floor rule, so
        //    a fixture claiming "G3" over a Kp 1.67 bin must change nothing
        //    at all — not the series, not the G scale.
        val nulls = parse(
            """[{"time_tag":"2026-07-18T00:00:00","kp":1.67,"observed":"observed","noaa_scale":null}]"""
        )
        val claimed = parse(
            """[{"time_tag":"2026-07-18T00:00:00","kp":1.67,"observed":"observed","noaa_scale":"G3"}]"""
        )
        assertNotNull(nulls)
        assertNotNull(claimed)
        assertEquals(nulls, claimed)
        assertEquals(GScale.G0, Geomagnetic.gScale(claimed!!.points.first().kp))
    }

    // ── 6. Kp is parsed as a Double, never string-matched ───────────

    @Test
    fun scientificNotationKpParsesAsADouble() {
        // 6. Sibling products emit `2.5999999e+000`, quoted in the legacy
        //    form and bare in the modern one. Only a real numeric parse
        //    survives either; matching on the digits would drop the row.
        val quoted = parse(
            """[{"time_tag":"2026-07-18T00:00:00","kp":"2.5999999e+000","observed":"observed"}]"""
        )
        assertNotNull(quoted)
        assertEquals(2.6, quoted!!.points.first().kp, 1e-6)

        val bare = parse(
            """[{"time_tag":"2026-07-18T00:00:00","kp":2.5999999e+000,"observed":"observed"}]"""
        )
        assertNotNull(bare)
        assertEquals(2.6, bare!!.points.first().kp, 1e-6)

        // Integers and quoted decimals land on the same Double too.
        val mixed = parse(
            """
            [{"time_tag":"2026-07-18T00:00:00","kp":5,"observed":"observed"},
             {"time_tag":"2026-07-18T03:00:00","kp":"5.0","observed":"observed"}]
            """.trimIndent()
        )
        assertNotNull(mixed)
        assertEquals(listOf(5.0, 5.0), mixed!!.points.map { it.kp })
    }

    // ── 7. Never a conditional GET, and never a query string ────────

    @Test
    fun endpointIsAConstantUrlWithNoQueryString() {
        // The property the privacy policy claims: the request is
        // byte-identical for every copy of the app on Earth. Spelled as a
        // literal constant so no parameter can be appended without anyone
        // noticing.
        assertEquals(
            "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json",
            SpaceWeatherRepository.FORECAST_URL
        )
        assertNull(URI(SpaceWeatherRepository.FORECAST_URL).query)
    }

    @Test
    fun requestIsNeverConditional() {
        // 7. SWPC rewrites its whole tree every minute, so `last-modified`
        //    is always seconds old regardless of when the content changed
        //    and an `If-Modified-Since` would never hit. Freshness comes
        //    from the payload's own `time_tag` and our fetch stamp instead.
        val request = SpaceWeatherRepository.forecastRequest()
        assertEquals("GET", request.method)
        assertEquals(SpaceWeatherRepository.FORECAST_URL, request.url.toString())
        assertNull(request.url.query)
        assertNull(request.header("If-Modified-Since"))
        assertNull(request.header("If-None-Match"))
        // And nothing about the user rides along beside them: two fixed
        // headers, no body, no cookie, no identifier of any kind.
        assertEquals(setOf("Accept", "User-Agent"), request.headers.names())
        assertNull(request.body)
    }

    // ── 8. A failed or empty fetch never overwrites a good cache ────

    @Test
    fun failedOrEmptyFetchNeverOverwritesAGoodCache() {
        // 8. A cache that still answers "you need about Kp 5+ here" beats an
        //    empty card, so only a successful, NON-EMPTY parse may replace
        //    it. `refreshLocked` writes only when
        //    `fetched != null && fetched.points.isNotEmpty()`; what is
        //    pinned here is that every failure shape really does land on one
        //    of the two values that guard refuses, and that the file on disk
        //    is untouched afterwards. An HTTP 500 never even reaches the
        //    parse — `fetch` returns null before the body is read.
        val cachedAtMs = fetchedAtMs - hours(9.0)
        val good = KpSeries(cachedAtMs, listOf(KpPoint(cachedAtMs, 6.0, KpProvenance.OBSERVED)))
        val store = SpaceWeatherStore(directory)
        runBlocking {
            store.write(good)

            for (body in listOf("{ not json", "", "null", "[]", "<html>502 Bad Gateway</html>")) {
                val fetched = parse(body)
                assertTrue(body, fetched == null || fetched.points.isEmpty())
                assertEquals(body, good, store.read())
            }

            // The control: a good product DOES replace it, so the loop above
            // is not passing because nothing can ever update.
            val fresh = parse(objectRows)
            assertNotNull(fresh)
            assertEquals(3, fresh!!.points.size)
            store.write(fresh)
            assertEquals(fresh, store.read())
            assertNotEquals(good, store.read())
        }
    }

    // ── 9. A row with a null or absent kp is skipped ────────────────

    @Test
    fun rowWithNullOrAbsentKpIsSkippedAndTheOthersSurvive() {
        // 9. One unusable bin is not a broken product. Dropping the whole
        //    series over it would empty a card that had every other bin.
        val series = parse(
            """
            [{"time_tag":"2026-07-18T00:00:00","kp":1.67,"observed":"observed","noaa_scale":null},
             {"time_tag":"2026-07-18T03:00:00","kp":null,"observed":"observed","noaa_scale":null},
             {"time_tag":"2026-07-18T06:00:00","observed":"estimated","noaa_scale":null},
             {"time_tag":"2026-07-18T09:00:00","kp":3.33,"observed":"predicted","noaa_scale":null}]
            """.trimIndent()
        )
        assertNotNull(series)
        assertEquals(2, series!!.points.size)
        assertEquals(listOf(1.67, 3.33), series.points.map { it.kp })

        // A row with no usable time_tag goes the same way.
        val badStamp = parse(
            """
            [{"time_tag":"not a date","kp":1.67,"observed":"observed"},
             {"time_tag":"2026-07-18T00:00:00","kp":2.33,"observed":"observed"}]
            """.trimIndent()
        )
        assertNotNull(badStamp)
        assertEquals(listOf(2.33), badStamp!!.points.map { it.kp })
    }

    // ── 10. Empty array is no data; malformed JSON is null ──────────

    @Test
    fun emptyArrayIsZeroBinsAndResolvesToNoData() {
        // 10. An empty array is a successful parse of zero bins, not a
        //     failure — and [Geomagnetic] resolves it to [Freshness.NONE]
        //     rather than "live with no value", or a crash.
        val series = parse("[]")
        assertNotNull(series)
        assertTrue(series!!.points.isEmpty())

        val conditions = Geomagnetic.conditions(
            series = series,
            latitude = 51.5074,
            longitude = -0.1278,
            grid = null,
            nowMs = fetchedAtMs,
            timeZone = utc
        )
        assertEquals(Freshness.NONE, conditions.freshness)
        assertNull(conditions.dataAgeMs)
        assertNull(conditions.kpNow)
        assertEquals(GScale.G0, conditions.gScale)
    }

    @Test
    fun malformedJsonReturnsNull() {
        // 10. Neither wire format parses, so the caller keeps what it has.
        //     Every one of these is a shape a truncated or proxied response
        //     can actually take.
        assertNull(parse("{ not json"))
        assertNull(parse(""))
        assertNull(parse("null"))
        assertNull(parse("""{"time_tag":"2026-07-18T00:00:00","kp":1.67}"""))
        assertNull(parse("[1,2,3]"))
        assertNull(parse("<html><body>502 Bad Gateway</body></html>"))
    }

    // ── Cache round-trip ────────────────────────────────────────────

    @Test
    fun cacheRoundTripsThroughDisk() {
        // Written by one launch, read by the next. Driven through the real
        // write and read paths rather than a copy of them, because the disk
        // type is deliberately NOT [KpSeries] — the mapping between the two
        // is the part that can silently drop a field.
        val series = KpSeries(
            fetchedAtMs = fetchedAtMs,
            points = listOf(
                KpPoint(fetchedAtMs - hours(3.0), 4.667, KpProvenance.OBSERVED),
                KpPoint(fetchedAtMs, 5.333, KpProvenance.ESTIMATED),
                KpPoint(fetchedAtMs + hours(3.0), 6.0, KpProvenance.PREDICTED)
            )
        )
        runBlocking {
            SpaceWeatherStore(directory).write(series)
            assertEquals(series, SpaceWeatherStore(directory).read())
        }
    }

    @Test
    fun legacyCacheBlobMissingTheNewestFieldStillDecodes() {
        // A cache written by an older build has no `provenance` on its
        // points. Every field of the disk type carries a DEFAULT precisely
        // so that file still decodes — the same forward/backward
        // compatibility rule `SharedSnapshotStoreTest`'s "legacy tokens
        // without lat-lon decode cleanly" pins for the widget snapshot —
        // instead of being discarded on the first launch after an update,
        // which is when a user is least able to refetch.
        //
        // The file name is spelled out rather than read from the store: it
        // is the on-disk contract with every build that shipped before this
        // one, so a rename has to fail a test rather than quietly orphan
        // everybody's cache.
        File(directory, "space_weather.json").writeText(
            """
            {"fetchedAtMs":1700000000000,
             "points":[{"timeMs":1699989200000,"kp":4.667},
                       {"timeMs":1700000000000,"kp":5.333}]}
            """.trimIndent()
        )

        val loaded = runBlocking { SpaceWeatherStore(directory).read() }
        assertNotNull(loaded)
        assertEquals(fetchedAtMs, loaded!!.fetchedAtMs)
        assertEquals(listOf(4.667, 5.333), loaded.points.map { it.kp })
        // The missing tag reads back as PREDICTED, which the card captions
        // "forecast" rather than claiming the number was observed.
        assertEquals(
            listOf(KpProvenance.PREDICTED, KpProvenance.PREDICTED),
            loaded.points.map { it.provenance }
        )
    }

    @Test
    fun cacheWithNoUsablePointsIsNotLoaded() {
        // Half a cache is not a cache. A payload with no bins in it leaves
        // the repository empty rather than handing back a series that has a
        // fetch stamp and nothing else, which the card would read as "live,
        // no value" instead of dropping to its offline tier.
        val file = File(directory, "space_weather.json")
        file.writeText("""{"fetchedAtMs":1700000000000,"points":[]}""")
        assertNull(runBlocking { SpaceWeatherStore(directory).read() })

        // A file truncated mid-write is the same answer — no cache, not a
        // crash and not a partial series.
        file.writeText("""{"fetchedAtMs":1700000000000,"points":[{"timeMs":""")
        assertNull(runBlocking { SpaceWeatherStore(directory).read() })

        // And so is a cold cache, before anything has ever been written.
        assertTrue(file.delete())
        assertNull(runBlocking { SpaceWeatherStore(directory).read() })
    }
}
