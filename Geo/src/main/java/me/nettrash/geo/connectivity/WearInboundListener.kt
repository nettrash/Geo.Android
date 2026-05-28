package me.nettrash.geo.connectivity

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.nettrash.geo.data.db.HistoryItem
import me.nettrash.geo.data.model.InformationToken
import me.nettrash.geo.data.repository.HistoryRepository
import me.nettrash.geo.data.snapshot.SharedSnapshotStore
import me.nettrash.geo.util.AppLog
import javax.inject.Inject
import kotlin.math.abs

/**
 * Receives barometer + GPS-context snapshots pushed from the paired
 * Wear OS device and folds them into the phone's Room history.
 *
 * Why this exists
 * ---------------
 * The iOS Geo Watch app streams barometer samples to the iPhone at
 * roughly 1 Hz; on iOS each inbound sample gets a `HistoryItem`
 * inserted into CoreData, deduplicated *by exact `recordDate`
 * equality*. That dedup never matches across two devices' wall
 * clocks, so the table grows ~3 600 rows/hour while the Watch is
 * worn. We avoid that on Android by throttling on both:
 *
 *   * **Pressure delta** — only persist when the watch's pressure
 *     has moved at least [PRESSURE_DELTA_KPA] since the last
 *     stored sample. Small flutter doesn't generate rows.
 *   * **Minimum interval** — even on real movement, never store
 *     more often than every [MIN_INTERVAL_MS]. Caps the worst
 *     case at ~120 rows/hour.
 *
 * The token is also fed into [SharedSnapshotStore] so the widget
 * and on-launch back-fill paths see watch-side data the same way
 * they see phone-side data.
 */
@AndroidEntryPoint
class WearInboundListener : WearableListenerService() {

    @Inject lateinit var historyRepository: HistoryRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_SNAPSHOT) return
        // `event.data` is declared non-nullable on the Play Services
        // side but can still be empty; treat zero bytes as "nothing
        // useful" rather than handing it to the JSON decoder.
        val payload = event.data
        if (payload.isEmpty()) return
        val text = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return
        val token = runCatching {
            json.decodeFromString<InformationToken>(text)
        }.getOrNull() ?: return
        if (token.barPreassure <= 0) return

        scope.launch { maybePersist(token) }
    }

    private suspend fun maybePersist(token: InformationToken) {
        val now = System.currentTimeMillis()
        val last = lastPersistedToken
        if (last != null) {
            val deltaKpa = abs(token.barPreassure - last.barPreassure)
            val deltaMs = now - lastPersistedAtMs
            if (deltaKpa < PRESSURE_DELTA_KPA && deltaMs < MIN_INTERVAL_MS) {
                AppLog.connectivity.debug(
                    "Inbound watch sample skipped: Δp=${"%.4f".format(deltaKpa)} kPa, " +
                        "Δt=${deltaMs}ms"
                )
                return
            }
        }
        lastPersistedToken = token
        lastPersistedAtMs = now

        // Insert as a regular HistoryItem; the watch's lat/lon are
        // forwarded as best-effort context (often zero when the
        // watch has no GPS, in which case they remain blank but
        // the barometer reading still has value).
        historyRepository.insert(
            HistoryItem(
                recordDate = token.recordDate.takeIf { it > 0 } ?: now,
                barometerAltitude = token.barAltitude,
                barometerPressure = token.barPreassure,
                gpsLatitude = token.gpsLatitude,
                gpsLongitude = token.gpsLongitude,
                gpsAltitude = token.gpsAltitude,
                gpsVelocity = token.gpsSpeed
            )
        )
        // Snapshot store stays in sync so widget/back-fill paths
        // observe watch-driven data uniformly with phone-driven data.
        SharedSnapshotStore.write(applicationContext, token)
        AppLog.connectivity.debug("Inbound watch sample persisted")
    }

    private companion object {
        const val PATH_SNAPSHOT = "/geo/snapshot"
        /** Skip inserts when pressure hasn't moved at least this much. */
        const val PRESSURE_DELTA_KPA = 0.1
        /** Hard minimum spacing between inserts, regardless of pressure delta. */
        const val MIN_INTERVAL_MS: Long = 30_000L

        // Static so the cooldown survives across short service
        // restarts (Android can bind/unbind WearableListenerService
        // freely; we'd otherwise lose throttle state between
        // messages and back-to-back ones would all get persisted).
        @Volatile var lastPersistedToken: InformationToken? = null
        @Volatile var lastPersistedAtMs: Long = 0L
    }
}
