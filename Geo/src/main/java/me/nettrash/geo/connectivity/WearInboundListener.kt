package me.nettrash.geo.connectivity

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Fallback delivery path. When the watch can't reach us live it
     * persists the snapshot as a `DataItem` (see the watch-side
     * `WearOutboundBridge` DataClient fallback / iOS
     * `updateApplicationContext`); the Data Layer replays it here when
     * the phone app next runs. We decode the same JSON payload and
     * funnel it through the same throttled [maybePersist] as live
     * messages, so a relaunch backfills the missed sample exactly once.
     */
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != PATH_SNAPSHOT) continue
            val payload = runCatching {
                DataMapItem.fromDataItem(item).dataMap.getByteArray(KEY_SNAPSHOT)
            }.getOrNull() ?: continue
            if (payload.isEmpty()) continue
            val text = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: continue
            val token = runCatching {
                json.decodeFromString<InformationToken>(text)
            }.getOrNull() ?: continue
            if (token.barPreassure <= 0) continue
            scope.launch { maybePersist(token) }
        }
    }

    private suspend fun maybePersist(token: InformationToken) = persistMutex.withLock {
        // The throttle is a read-decide-write over shared static state.
        // Each inbound message is dispatched on the multi-threaded
        // Dispatchers.IO pool, so without a lock two near-simultaneous
        // samples could both read the same `last`/`lastPersistedAtMs`,
        // both pass the gate, and both insert — partially defeating the
        // 30 s / 0.1 kPa cap. Holding the mutex across the decision AND
        // the state update keeps the check-then-act atomic.
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
                return@withLock
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
        /** DataMap key the watch-side DataClient fallback writes under. */
        const val KEY_SNAPSHOT = "snapshot"
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

        // Serializes the read-decide-write throttle sequence above so
        // concurrent Dispatchers.IO coroutines can't both pass the gate
        // on the same stale state and double-insert. Static for the
        // same reason as the fields it guards.
        private val persistMutex = Mutex()
    }
}
