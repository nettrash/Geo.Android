package me.nettrash.geo.connectivity

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nettrash.geo.data.model.InformationToken
import me.nettrash.geo.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side outbound bridge. Mirrors iOS
 * `PhoneConnectivityManager.sendCurrentSnapshot(_:)`.
 *
 * Encodes [InformationToken] as JSON and ships it to every paired
 * watch under the `/geo/snapshot` path. The Wear app's
 * [me.nettrash.geo.wear.WearableSnapshotListener] decodes it back
 * and feeds the in-process snapshot store the Watch UI subscribes
 * to.
 *
 * Best-effort and fire-and-forget. The Wearable API gracefully
 * no-ops on devices with no paired watch.
 */
@Singleton
class WearMessageBridge @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val json = Json { encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(token: InformationToken) {
        scope.launch {
            try {
                val payload = json.encodeToString(token).toByteArray(Charsets.UTF_8)
                val nodeClient = Wearable.getNodeClient(context)
                val messageClient = Wearable.getMessageClient(context)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                for (node in nodes) {
                    try {
                        Tasks.await(messageClient.sendMessage(node.id, PATH_SNAPSHOT, payload))
                    } catch (t: Throwable) {
                        AppLog.connectivity.warn("Failed to send to wear node ${node.displayName}", t)
                    }
                }
            } catch (t: Throwable) {
                AppLog.connectivity.warn("WearMessageBridge.send failed", t)
            }
        }
    }

    companion object {
        const val PATH_SNAPSHOT = "/geo/snapshot"
    }
}
