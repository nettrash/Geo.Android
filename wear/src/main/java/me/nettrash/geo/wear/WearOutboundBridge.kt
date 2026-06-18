package me.nettrash.geo.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Watch-side outbound bridge. Mirrors iOS
 * `WatchConnectivityManager.sendCurrentSnapshot(_:)`.
 *
 * On each [WearBarometerManager] update it builds a
 * [WearInformationToken] from the latest watch barometer sample (and
 * whatever GPS context the phone last pushed) and ships it to the
 * paired phone under `/geo/snapshot`. The phone's
 * `me.nettrash.geo.connectivity.WearInboundListener` decodes it and
 * folds the barometer reading into the phone's Room history — the
 * watch->phone backfill flow iOS already has.
 *
 * Transport, mirroring iOS:
 *  * `MessageClient.sendMessage` — the live, fire-and-forget path,
 *    delivered only when the phone app is running (iOS
 *    `session.sendMessageData`).
 *  * `DataClient.putDataItem` fallback — a persisted item the system
 *    re-delivers when the phone app next starts, for when it is not
 *    currently running (iOS `session.updateApplicationContext`).
 *
 * Throttled identically to the phone-side inbound listener
 * (`WearInboundListener`): skip a send when pressure has moved less
 * than [PRESSURE_DELTA_KPA] AND less than [MIN_INTERVAL_MS] has
 * elapsed, so the watch's ~1 Hz stream doesn't flood the link.
 */
class WearOutboundBridge(context: Context) {

    private val appContext = context.applicationContext

    private val json = Json { encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Throttle state — guarded by [mutex] so the read-decide-write is
    // atomic across the IO coroutines launched per update.
    private val mutex = Mutex()
    private var lastSentToken: WearInformationToken? = null
    private var lastSentAtMs: Long = 0L

    /**
     * Offer the latest watch reading for transmission. Returns
     * immediately; the throttle check + send run on a background
     * coroutine. Safe to call on every barometer tick.
     */
    fun offer(token: WearInformationToken) {
        scope.launch {
            if (!shouldSend(token)) return@launch
            send(token)
        }
    }

    private suspend fun shouldSend(token: WearInformationToken): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        val last = lastSentToken
        if (last != null) {
            val deltaKpa = abs(token.barPreassure - last.barPreassure)
            val deltaMs = now - lastSentAtMs
            if (deltaKpa < PRESSURE_DELTA_KPA && deltaMs < MIN_INTERVAL_MS) {
                return@withLock false
            }
        }
        lastSentToken = token
        lastSentAtMs = now
        true
    }

    private fun send(token: WearInformationToken) {
        val text = json.encodeToString(token)
        val payload = text.toByteArray(Charsets.UTF_8)

        // Live path: message every connected node. Best-effort.
        var delivered = false
        runCatching {
            val nodeClient = Wearable.getNodeClient(appContext)
            val messageClient = Wearable.getMessageClient(appContext)
            val nodes = Tasks.await(nodeClient.connectedNodes)
            for (node in nodes) {
                runCatching {
                    Tasks.await(messageClient.sendMessage(node.id, PATH_SNAPSHOT, payload))
                    delivered = true
                }
            }
        }

        // Fallback: persist a DataItem the phone re-reads on next start
        // (analogous to iOS updateApplicationContext). The changing
        // timestamp keeps the item from being de-duplicated by the
        // Data Layer when pressure is momentarily unchanged.
        runCatching {
            val request = PutDataMapRequest.create(PATH_SNAPSHOT).apply {
                dataMap.putByteArray(KEY_SNAPSHOT, payload)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Tasks.await(Wearable.getDataClient(appContext).putDataItem(request))
        }.onFailure {
            // If even the fallback fails and nothing was delivered,
            // allow the next tick to retry by clearing the throttle.
            if (!delivered) {
                scope.launch { mutex.withLock { lastSentToken = null } }
            }
        }
    }

    companion object {
        const val PATH_SNAPSHOT = "/geo/snapshot"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_TIMESTAMP = "timestamp"

        /** Skip sends when pressure hasn't moved at least this much. */
        private const val PRESSURE_DELTA_KPA = 0.1
        /** Hard minimum spacing between sends, regardless of delta. */
        private const val MIN_INTERVAL_MS: Long = 30_000L
    }
}
