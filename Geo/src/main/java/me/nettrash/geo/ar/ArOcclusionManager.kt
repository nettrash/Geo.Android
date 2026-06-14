package me.nettrash.geo.ar

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides which AR markers sit behind real-world geometry. Mirrors
 * iOS `Nature/AROcclusionManager.swift` but uses the ARCore
 * primitives that are practical on Android:
 *
 *   1. **Vertical-plane intersection** (works on every ARCore device):
 *      shoot a ray from the camera through the marker and see if any
 *      detected vertical plane lies between the two.
 *
 *   2. **Depth-image sampling** (devices with `Config.DepthMode.AUTOMATIC`
 *      support): project the marker to screen, look up the depth at
 *      that pixel, and treat the marker as occluded when the scene
 *      depth is meaningfully closer than the marker's camera-space
 *      depth.
 *
 * Both signals feed a confidence counter so a single noisy plane fit
 * doesn't flicker a marker on / off — exactly the iOS behaviour.
 *
 * Outdoor heuristic: when the user is likely outdoors (poor GPS
 * accuracy OR many distant peaks visible), plane occlusion is
 * disabled because the vertical "walls" ARCore detects in those
 * environments are almost always noise. Depth occlusion still runs
 * because depth values genuinely correlate with scene geometry.
 */
@Singleton
class ArOcclusionManager @Inject constructor() {

    /** IDs of [OcclusionTarget]s currently judged to be behind real
     *  geometry. Consumed by `ProjectedOverlay` to hide markers. */
    private val _occludedIds = MutableStateFlow<Set<UUID>>(emptySet())
    val occludedIds: StateFlow<Set<UUID>> = _occludedIds.asStateFlow()

    /** Flips true once we've judged the user to be outdoors at least
     *  once this session — `NatureScreen` shows it as a top-bar
     *  badge so people understand why occlusion is muted. */
    private val _isOutdoor = MutableStateFlow(false)
    val isOutdoor: StateFlow<Boolean> = _isOutdoor.asStateFlow()

    /**
     * Whether the AR session has collected enough scene data for
     * reliable occlusion. Starts false; becomes true once a vertical
     * plane or a depth frame is available, or after a short warm-up
     * timeout (see [sessionStarted]).
     *
     * `NatureScreen` uses this exactly like iOS `isSceneReady`: near
     * markers (< `nearbyThreshold` m) are suppressed until it flips so
     * they don't flash in before the scene can occlude them, and a
     * "Scanning" indicator shows while it's false. Far markers are
     * unaffected. Mirrors iOS `AROcclusionManager.isSceneReady`.
     */
    private val _isSceneReady = MutableStateFlow(false)
    val isSceneReady: StateFlow<Boolean> = _isSceneReady.asStateFlow()

    /** Warm-up fallback: even with no planes/depth (e.g. outdoors with
     *  nothing nearby) the scene is treated as ready after this many
     *  milliseconds so near markers aren't hidden forever. Mirrors the
     *  iOS 3-second `sessionDidStart` fallback. */
    private val sceneWarmupMs = 3_000L

    private var warmupJob: Job? = null

    /** Marker beyond this many metres is never tested for occlusion
     *  — plane / depth data can't say anything useful about a peak
     *  30 km away. Mirrors iOS `maxOcclusionTargetDistance`. */
    private val maxTargetDistanceM = 200f

    /** Plane occlusion only applies to markers within room-scale
     *  range (iOS uses the same 30 m threshold). */
    private val maxPlaneDistanceM = 30f

    /** Consecutive consistent reads needed to flip the occluded
     *  state for a marker (anti-flicker). */
    private val confidenceThreshold = 2

    /** Per-ID counter, in [0, confidenceThreshold]. */
    private val confidence = HashMap<UUID, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null

    /** A point that may or may not be occluded by real geometry. */
    data class OcclusionTarget(
        val id: UUID,
        /** World-space position in ARCore frame (East, Up, −North). */
        val worldPosition: FloatArray
    )

    /** Set the outdoor flag from the host. The caller computes the
     *  heuristic because it has the user's location accuracy and
     *  peak-list at hand. */
    fun setOutdoor(value: Boolean) {
        _isOutdoor.value = value
    }

    /**
     * Call when an AR session starts (or restarts). Resets the
     * scene-ready gate and schedules the warm-up fallback so near
     * markers eventually appear even when ARCore detects no planes or
     * depth. Mirrors iOS `AROcclusionManager.sessionDidStart`.
     */
    fun sessionStarted() {
        warmupJob?.cancel()
        _isSceneReady.value = false
        warmupJob = scope.launch {
            delay(sceneWarmupMs)
            if (!_isSceneReady.value) {
                _isSceneReady.value = true
            }
        }
    }

    /**
     * Recompute occlusion for the given [targets]. Coroutine-based
     * so the heavy math doesn't block the AR frame callback. New
     * calls cancel any in-flight one — we'd rather report stale data
     * for one tick than queue work that's already obsolete.
     */
    fun check(
        targets: List<OcclusionTarget>,
        controller: ArSceneController
    ) {
        currentJob?.cancel()
        val planes = controller.verticalPlanes.value
        val cameraPos = controller.cameraPosition.value
        val depthSnapshot = controller.depthSnapshot.value
        val viewport = controller.viewportSize.value
        val outdoor = _isOutdoor.value

        // Scene-ready signal: as soon as ARCore gives us a vertical
        // plane or a depth frame, the scene can occlude near markers,
        // so the warm-up gate can lift early. Mirrors iOS flipping
        // `isSceneReady` on first mesh / vertical-plane detection.
        if (!_isSceneReady.value && (planes.isNotEmpty() || depthSnapshot != null)) {
            _isSceneReady.value = true
        }

        if (cameraPos == null) {
            // Nothing to do until ARCore reports a camera pose.
            return
        }

        currentJob = scope.launch {
            val nowOccluded = withContext(Dispatchers.Default) {
                val occluded = HashSet<UUID>()
                for (target in targets) {
                    val dx = target.worldPosition[0] - cameraPos[0]
                    val dy = target.worldPosition[1] - cameraPos[1]
                    val dz = target.worldPosition[2] - cameraPos[2]
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    if (dist < 0.1f || dist > maxTargetDistanceM) continue

                    // 1. Plane occlusion — only when indoors AND within
                    //    room-scale range.
                    if (!outdoor && dist < maxPlaneDistanceM &&
                        isOccludedByPlanes(cameraPos, target.worldPosition, dist, planes)
                    ) {
                        occluded.add(target.id)
                        continue
                    }

                    // 2. Depth-image occlusion — projection + lookup.
                    if (depthSnapshot != null && viewport != null) {
                        val screen = controller.projectToScreen(target.worldPosition) ?: continue
                        val occ = isOccludedByDepth(
                            screen = screen,
                            viewport = viewport,
                            cameraSpaceDepth = projectCameraSpaceDepth(controller, target.worldPosition),
                            depth = depthSnapshot
                        )
                        if (occ) occluded.add(target.id)
                    }
                }
                occluded
            }

            // Confidence stabilisation — mirrors iOS exactly.
            val allIds = targets.asSequence().map { it.id }.toHashSet()
            val stable = HashSet(_occludedIds.value)
            for (id in allIds) {
                if (nowOccluded.contains(id)) {
                    val c = ((confidence[id] ?: 0) + 1).coerceAtMost(confidenceThreshold)
                    confidence[id] = c
                    if (c >= confidenceThreshold) stable.add(id)
                } else {
                    val c = ((confidence[id] ?: 0) - 1).coerceAtLeast(0)
                    confidence[id] = c
                    if (c == 0) stable.remove(id)
                }
            }
            // Drop confidence entries for IDs no longer in the
            // target list so the map doesn't grow forever.
            confidence.keys.retainAll(allIds)

            if (stable != _occludedIds.value) {
                _occludedIds.value = stable
            }
        }
    }

    /** Stop and discard any in-flight work — call when AR session
     *  tears down so the scope doesn't outlive the host. */
    fun shutdown() {
        currentJob?.cancel()
        warmupJob?.cancel()
        scope.cancel()
    }

    // ── Plane ray-intersection (pure math, unit-testable) ─────────

    /** True if the ray from `cameraPos` to `targetPos` (length
     *  `distance`) is interrupted by any of the supplied planes. */
    internal fun isOccludedByPlanes(
        cameraPos: FloatArray,
        targetPos: FloatArray,
        distance: Float,
        planes: List<ArSceneController.PlaneSnapshot>
    ): Boolean {
        if (planes.isEmpty()) return false
        val dir = floatArrayOf(
            (targetPos[0] - cameraPos[0]) / distance,
            (targetPos[1] - cameraPos[1]) / distance,
            (targetPos[2] - cameraPos[2]) / distance
        )
        for (plane in planes) {
            val mat = plane.transform
            // Plane normal is the Y axis of its local frame (column 1
            // of the column-major transform).
            val nx = mat[4]
            val ny = mat[5]
            val nz = mat[6]
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            if (nLen < 1e-6f) continue
            val normalX = nx / nLen
            val normalY = ny / nLen
            val normalZ = nz / nLen

            val centerX = mat[12]
            val centerY = mat[13]
            val centerZ = mat[14]

            val denom = dir[0] * normalX + dir[1] * normalY + dir[2] * normalZ
            if (abs(denom) < 1e-6f) continue

            val t = ((centerX - cameraPos[0]) * normalX +
                (centerY - cameraPos[1]) * normalY +
                (centerZ - cameraPos[2]) * normalZ) / denom
            if (t <= 0.1f || t >= distance - 0.1f) continue

            // Check hit point falls within the plane's detected extent.
            val hitX = cameraPos[0] + dir[0] * t
            val hitY = cameraPos[1] + dir[1] * t
            val hitZ = cameraPos[2] + dir[2] * t

            // Inverse rigid transform (orthonormal rotation + translation).
            // local = R^T * (hit - center); R is columns 0..2 of `mat`.
            val rx = hitX - centerX
            val ry = hitY - centerY
            val rz = hitZ - centerZ

            // R is column-major; R^T's row i is mat's column i.
            val localX = mat[0] * rx + mat[1] * ry + mat[2] * rz
            val localZ = mat[8] * rx + mat[9] * ry + mat[10] * rz

            val halfX = plane.extentX * 0.5f + 0.1f
            val halfZ = plane.extentZ * 0.5f + 0.1f
            if (abs(localX) <= halfX && abs(localZ) <= halfZ) {
                return true
            }
        }
        return false
    }

    // ── Depth-image sampling ──────────────────────────────────────

    /**
     * Look up the scene depth at the marker's screen position. We
     * treat the marker as occluded when the scene is meaningfully
     * closer than the marker (`scene + 0.3 m < markerDepth`) — the
     * 30 cm tolerance avoids false positives near plane edges.
     */
    internal fun isOccludedByDepth(
        screen: Offset,
        viewport: ArSceneController.IntSize,
        cameraSpaceDepth: Float,
        depth: ArSceneController.DepthSnapshot
    ): Boolean {
        if (cameraSpaceDepth <= 0f) return false
        if (depth.widthPx <= 0 || depth.heightPx <= 0) return false

        // Convert viewport pixels → depth-image pixels. The depth
        // image is typically much lower resolution than the viewport
        // (e.g. 240×180 for a 2400×1080 screen on a Pixel 6) and is
        // rotated to match the camera image, so the simple ratio
        // approach below is correct in practice. (A perfect mapping
        // requires the display transform from ARCore; this
        // approximation is within a pixel of correct for the usage
        // we have.)
        val nx = (screen.x / viewport.width).coerceIn(0f, 1f)
        val ny = (screen.y / viewport.height).coerceIn(0f, 1f)
        val dx = (nx * depth.widthPx).toInt().coerceAtMost(depth.widthPx - 1)
        val dy = (ny * depth.heightPx).toInt().coerceAtMost(depth.heightPx - 1)
        val byteOffset = dy * depth.rowStrideBytes + dx * 2
        if (byteOffset + 1 >= depth.pixels.size) return false

        // ARCore returns U16 in millimetres, native byte order. The
        // `ByteBuffer` underlying the Image has its native order,
        // which on Android is little-endian. Decode as LE.
        val low = depth.pixels[byteOffset].toInt() and 0xFF
        val high = depth.pixels[byteOffset + 1].toInt() and 0xFF
        val depthMm = (high shl 8) or low
        if (depthMm <= 0) return false  // unmeasured pixel

        val sceneDepthM = depthMm / 1000f
        // Marker is "behind" the surface when the surface is
        // meaningfully closer.
        return sceneDepthM > 0.1f && sceneDepthM < cameraSpaceDepth - 0.3f
    }

    private fun projectCameraSpaceDepth(
        controller: ArSceneController,
        world: FloatArray
    ): Float {
        // ArSceneController doesn't expose this directly; compute it
        // here from the view matrix.
        val view = controller.viewMatrix.value ?: return 0f
        // view * (world, 1) → camera-space; depth is −Z of the
        // resulting vector (ARCore camera looks down −Z).
        val z = view[2] * world[0] + view[6] * world[1] + view[10] * world[2] + view[14]
        return -z
    }
}
