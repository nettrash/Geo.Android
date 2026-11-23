package me.nettrash.geo.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the two rules that turned a loaded Overpass instance into a
 * permanently empty Nature view (#26): when a previous nearby-peak result may
 * be reused, and the mirror list a search fails over across.
 *
 * Both live on [PeakFinder]'s companion and are pure, so no network and no
 * location are involved — exactly the behaviours a live device cannot
 * reproduce deterministically. Mirrors iOS `PeakFinderResilienceTests`.
 *
 * Plain JUnit rather than the suite's usual `RobolectricTestRunner`: nothing
 * here touches `android.*` (both subjects are companion functions, and the only
 * Android type in the signature is reached through the companion), so the tests
 * run on the JVM without a Robolectric sandbox — which also keeps them running
 * on JDKs new enough that the pinned Robolectric refuses to instrument
 * (`Unsupported class file major version`). The search itself — failover, an
 * outage, the stale flag, the snapshot — is driven end to end in
 * [PeakFinderOutageTest], which does need Robolectric.
 */
class PeakFinderResilienceTest {

    /** Mirrors the production tuning; the tests describe the *rules*, not the
     *  numbers, so retuning the constants in `PeakFinder` doesn't need edits. */
    private val maxCacheAgeMs = 15L * 60L * 1000L
    private val retryBackoffMs = 60L * 1000L
    private val now = 1_700_000_000_000L

    private fun canReuse(successAgeMs: Long?, failureAgeMs: Long?) = PeakFinder.canReuseResult(
        nowMs = now,
        lastSuccessAtMs = successAgeMs?.let { now + it },
        lastFailureAtMs = failureAgeMs?.let { now + it },
        maxCacheAgeMs = maxCacheAgeMs,
        retryBackoffMs = retryBackoffMs
    )

    @Test fun nothingSearchedYetIsNeverReused() {
        assertThat(canReuse(successAgeMs = null, failureAgeMs = null)).isFalse()
    }

    @Test fun freshResultIsReusedWithoutAskingTheNetwork() {
        assertThat(canReuse(successAgeMs = -60_000L, failureAgeMs = null)).isTrue()
    }

    /** The regression: the old guard only looked at distance, so a stationary
     *  user never refetched — and with Overpass unhealthy that meant forever. */
    @Test fun staleResultIsRefetchedEvenWithoutMovement() {
        assertThat(canReuse(successAgeMs = -2 * maxCacheAgeMs, failureAgeMs = null)).isFalse()
    }

    /** Once stale, the result is still shown while the backoff from a failed
     *  attempt runs: the peaks on screen are never dropped for nothing, and a
     *  loaded endpoint is not hammered on every refresh tick. */
    @Test fun staleResultIsKeptWhileRetryBackoffRuns() {
        assertThat(canReuse(-2 * maxCacheAgeMs, -retryBackoffMs / 2)).isTrue()
    }

    @Test fun staleResultIsRefetchedOnceBackoffExpires() {
        assertThat(canReuse(-2 * maxCacheAgeMs, -2 * retryBackoffMs)).isFalse()
    }

    /** Nothing has ever loaded and the last attempt failed seconds ago: the
     *  screen stays as it is instead of firing another request at a loaded
     *  mirror on every refresh tick. This is the cold-start-in-an-outage case,
     *  and the backoff is the only thing that keeps the client from burying a
     *  shared public endpoint. */
    @Test fun failedSearchIsNotRetriedUntilBackoffExpiresWithNothingOnScreen() {
        assertThat(canReuse(successAgeMs = null, failureAgeMs = -retryBackoffMs / 2)).isTrue()
        assertThat(canReuse(successAgeMs = null, failureAgeMs = -2 * retryBackoffMs)).isFalse()
    }

    /** Exactly the three instances that hold GLOBAL data, in preference order.
     *  A regional one answers HTTP 200 with zero peaks everywhere outside its
     *  region (overpass.osm.ch is Switzerland only: 0 peaks at Mont Blanc where
     *  the others return 83), and the search must take any answer as "confirmed:
     *  no peaks here" — so a list that merely contains the main instance pins
     *  nothing. */
    @Test fun mirrorsAreExactlyTheThreeGlobalInstances() {
        assertThat(PeakFinder.overpassEndpoints).containsExactly(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.openstreetmap.fr/api/interpreter"
        ).inOrder()
    }

    @Test fun rotationVisitsEveryMirrorExactlyOnce() {
        val count = PeakFinder.overpassEndpoints.size
        for (start in listOf(-4, -1, 0, 1, count, count + 7)) {
            val rotated = PeakFinder.rotatedEndpoints(start)
            assertThat(rotated).hasSize(count)
            assertThat(rotated.toSet())
                .containsExactlyElementsIn(PeakFinder.overpassEndpoints.toSet())
        }
        assertThat(PeakFinder.rotatedEndpoints(1).first())
            .isEqualTo(PeakFinder.overpassEndpoints[1])
    }
}
