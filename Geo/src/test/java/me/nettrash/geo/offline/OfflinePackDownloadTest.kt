package me.nettrash.geo.offline

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.nettrash.geo.R
import me.nettrash.geo.ar.TerrainElevationService
import me.nettrash.geo.util.OverpassStub
import me.nettrash.geo.util.PeakFinder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A pack download that never got an answer must not be saved as "this area has
 * no peaks" (#26). The pack fetch used to collapse "no peak server answered"
 * into an empty list, so a failed download saved a 0-peak pack — which, on a
 * summit with no signal, reads exactly like a valley without mountains.
 *
 * Driven through the real [OfflinePackRepository] and [PeakFinder], with
 * [OverpassStub] in place of the network, and checked against what is on disk.
 * Mirrors iOS `OfflinePackManager`.
 */
@RunWith(RobolectricTestRunner::class)
class OfflinePackDownloadTest {

    private val ctx: Context by lazy { RuntimeEnvironment.getApplication() }

    /** Pack centre (above Zermatt) and a summit ~1.6 km from it. */
    private val centerLat = 45.9763
    private val centerLon = 7.6586
    private val valleyPeak = OverpassStub.Peak("Valley Peak", 45.9900, 7.6650, 3200.0)

    private val failedMessage: String get() = ctx.getString(R.string.offline_download_failed)

    @Before
    fun clean() = removePacks()

    @After
    fun cleanUp() = removePacks()

    private fun removePacks() {
        File(ctx.filesDir, "offline_packs").deleteRecursively()
    }

    private fun repository(stub: OverpassStub): OfflinePackRepository {
        val finder = PeakFinder(TerrainElevationService(ctx), ctx).apply { client = stub.client() }
        return OfflinePackRepository(ctx, finder)
    }

    private suspend fun savedPacks(): List<OfflinePack> = OfflinePackStore(ctx).loadIndex()

    @Test
    fun aFailedDownloadSavesNothingAndSaysWhy() = runBlocking<Unit> {
        val repo = repository(OverpassStub { null })
        repo.createPack("Valley", centerLat, centerLon, 10.0)

        assertThat(savedPacks()).isEmpty()
        assertThat(repo.downloadError.value).isEqualTo(failedMessage)
        assertThat(repo.isDownloading.value).isFalse()

        // Dismissible.
        repo.clearDownloadError()
        assertThat(repo.downloadError.value).isNull()
    }

    /** An answer with no peaks is a real answer — flat country — and is kept. */
    @Test
    fun anAnswerWithNoPeaksIsStillSaved() = runBlocking<Unit> {
        val repo = repository(OverpassStub { OverpassStub.body(emptyList()) })
        repo.createPack("Flatland", centerLat, centerLon, 10.0)

        val packs = savedPacks()
        assertThat(packs).hasSize(1)
        assertThat(packs.single().peakCount).isEqualTo(0)
        assertThat(repo.downloadError.value).isNull()
    }

    /** An update that gets no answer keeps the pack exactly as it was, and says so. */
    @Test
    fun aFailedUpdateKeepsThePackAndSaysWhy() = runBlocking<Unit> {
        val stub = OverpassStub { OverpassStub.body(listOf(valleyPeak)) }
        val repo = repository(stub)
        repo.createPack("Valley", centerLat, centerLon, 10.0)
        val pack = savedPacks().single()

        stub.answer = { null }
        repo.updatePack(pack)

        assertThat(savedPacks().single().peakCount).isEqualTo(1)
        assertThat(OfflinePackStore(ctx).loadData(pack.id)!!.peaks.map { it.name })
            .containsExactly("Valley Peak")
        assertThat(repo.downloadError.value).isEqualTo(failedMessage)
    }

    /** The error belongs to the last attempt: the next download clears it as
     *  soon as it starts, not only once it succeeds. */
    @Test
    fun startingTheNextDownloadClearsTheError() = runBlocking<Unit> {
        val stub = OverpassStub { null }
        val repo = repository(stub)
        repo.createPack("Valley", centerLat, centerLon, 10.0)
        assertThat(repo.downloadError.value).isEqualTo(failedMessage)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        stub.answer = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            OverpassStub.body(listOf(valleyPeak))
        }
        val retry = async(Dispatchers.IO) { repo.createPack("Valley", centerLat, centerLon, 10.0) }
        val retryStarted = entered.await(5, TimeUnit.SECONDS)
        val errorWhileRetrying = repo.downloadError.value
        release.countDown()
        retry.await()

        assertThat(retryStarted).isTrue()
        assertThat(errorWhileRetrying).isNull()
        assertThat(repo.downloadError.value).isNull()
        assertThat(savedPacks()).hasSize(1)
    }
}
