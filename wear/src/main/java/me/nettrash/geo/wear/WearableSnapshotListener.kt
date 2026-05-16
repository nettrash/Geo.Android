package me.nettrash.geo.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.json.Json

/**
 * Listens for inbound `/geo/snapshot` messages from the paired
 * phone. Mirrors iOS WatchConnectivityManager's
 * `session(_:didReceiveMessageData:)` path: decode the payload and
 * forward to the in-process snapshot store the UI is observing.
 */
class WearableSnapshotListener : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_SNAPSHOT) return
        val payload = event.data ?: return
        val text = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return
        val token = runCatching {
            json.decodeFromString<WearInformationToken>(text)
        }.getOrNull() ?: return
        WearSnapshotStore.update(token)
    }

    companion object {
        const val PATH_SNAPSHOT = "/geo/snapshot"
    }
}
