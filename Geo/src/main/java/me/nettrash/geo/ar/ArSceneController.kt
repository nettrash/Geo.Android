package me.nettrash.geo.ar

import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-frame AR camera state, mirroring iOS `Nature/ARSessionManager`.
 *
 * The Android AR pipeline (ARCore + SceneView) doesn't expose a
 * publishable observable for camera matrices the way SwiftUI does
 * with `@Published`, so we wrap the values in StateFlows here and
 * have the per-frame callback in `NatureScreen` push updates.
 *
 * Held by an AR-scope composable in `NatureScreen` (not Hilt-scoped)
 * so each session start gets a fresh instance — the matrices from the
 * previous session would be meaningless after a pause/resume.
 */
class ArSceneController {

    /** Latest world→view matrix (4×4, row-major) or null until first frame. */
    private val _viewMatrix = MutableStateFlow<FloatArray?>(null)
    val viewMatrix: StateFlow<FloatArray?> = _viewMatrix.asStateFlow()

    /** Latest projection matrix (4×4, row-major). */
    private val _projectionMatrix = MutableStateFlow<FloatArray?>(null)
    val projectionMatrix: StateFlow<FloatArray?> = _projectionMatrix.asStateFlow()

    /** Camera world-position (Vec3) — ARCore's session origin. */
    private val _cameraPosition = MutableStateFlow<FloatArray?>(null)
    val cameraPosition: StateFlow<FloatArray?> = _cameraPosition.asStateFlow()

    /** Current viewport (px). */
    private val _viewportSize = MutableStateFlow<IntSize?>(null)
    val viewportSize: StateFlow<IntSize?> = _viewportSize.asStateFlow()

    /** True when ARCore reports `TrackingState.TRACKING`. */
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    /**
     * Distance (m) from the camera to whatever the centre of the
     * viewport is pointing at, derived via an ARCore hit-test on the
     * latest frame. `null` when no surface was detected.
     *
     * Mirrors iOS `ARSessionManager.raycastDistance` (kept under a
     * single name because the ARCore API uses "hit-test" while
     * iOS calls it "raycast" — the result is the same).
     */
    private val _wallDistance = MutableStateFlow<Float?>(null)
    val wallDistance: StateFlow<Float?> = _wallDistance.asStateFlow()

    /** Label for the current distance source — surfaces in the UI so
     *  the user knows whether the number is from a plane fit, a
     *  hit-test, or the ARCore depth API. */
    enum class DistanceSource { RAYCAST, DEPTH, PLANE }

    private val _distanceSource = MutableStateFlow<DistanceSource?>(null)
    val distanceSource: StateFlow<DistanceSource?> = _distanceSource.asStateFlow()

    /**
     * Snapshots of vertical planes detected by ARCore. Used by
     * [ArOcclusionManager] to hide markers that sit behind detected
     * walls indoors.
     *
     * We snapshot rather than expose the live `Plane` trackables so
     * the occlusion math can run off the main thread without
     * touching ARCore's not-thread-safe handles.
     */
    private val _verticalPlanes = MutableStateFlow<List<PlaneSnapshot>>(emptyList())
    val verticalPlanes: StateFlow<List<PlaneSnapshot>> = _verticalPlanes.asStateFlow()

    /**
     * Latest per-pixel depth (mm, Big-endian U16) plus its dimensions
     * and display transform. `null` when the depth API isn't
     * supported on this device or the frame didn't carry one.
     *
     * The byte buffer is copied out of the ARCore Image so the
     * occlusion thread can read it after the frame is released.
     */
    private val _depthSnapshot = MutableStateFlow<DepthSnapshot?>(null)
    val depthSnapshot: StateFlow<DepthSnapshot?> = _depthSnapshot.asStateFlow()

    /** True once the session has confirmed depth API support. */
    private val _isDepthSupported = MutableStateFlow(false)
    val isDepthSupported: StateFlow<Boolean> = _isDepthSupported.asStateFlow()

    /** Called by the AR session configuration callback once we
     *  know whether the device supports depth mode. */
    fun setDepthSupported(supported: Boolean) {
        _isDepthSupported.value = supported
    }

    /**
     * Called by the ARSceneView frame listener every ~16 ms. Cheap
     * — pulls a handful of matrices off ARCore and pushes them into
     * the StateFlows. Skips quietly when the camera isn't tracking
     * (matrices would be garbage anyway).
     *
     * [session] is needed to enumerate trackables (vertical planes)
     * for the occlusion path; pass `null` when only the matrices are
     * needed.
     */
    fun update(session: Session?, frame: Frame, viewportWidthPx: Int, viewportHeightPx: Int) {
        val cam = frame.camera
        _isTracking.value = cam.trackingState == TrackingState.TRACKING
        _viewportSize.value = IntSize(viewportWidthPx, viewportHeightPx)
        if (!_isTracking.value) return

        // 4×4 matrices, column-major in OpenGL — but ARCore's
        // getViewMatrix / getProjectionMatrix already returns column
        // major as a FloatArray(16). We store the raw column-major
        // form and the projection helper does the math against it
        // directly, so no transpose needed here.
        val view = FloatArray(16)
        cam.getViewMatrix(view, 0)
        _viewMatrix.value = view

        val proj = FloatArray(16)
        cam.getProjectionMatrix(proj, 0, 0.05f, 1_000f)
        _projectionMatrix.value = proj

        val pos = FloatArray(3)
        cam.pose.getTranslation(pos, 0)
        _cameraPosition.value = pos

        // Best-effort centre-of-viewport hit-test. ARCore picks the
        // closest qualifying anchor along the screen-centre ray.
        // We try a few result types in priority order and pick the
        // first that fires — mirrors iOS's LiDAR → raycast → plane
        // ordering.
        try {
            val centreX = viewportWidthPx / 2f
            val centreY = viewportHeightPx / 2f
            val hits = frame.hitTest(centreX, centreY)
            if (hits.isNotEmpty()) {
                // Prefer depth-API hits, then estimated-plane, then anything else.
                val first = hits.firstOrNull { it.trackable is com.google.ar.core.DepthPoint }
                    ?: hits.firstOrNull { it.trackable is com.google.ar.core.Plane }
                    ?: hits.first()
                val cameraToHit = floatArrayOf(
                    first.hitPose.tx() - pos[0],
                    first.hitPose.ty() - pos[1],
                    first.hitPose.tz() - pos[2]
                )
                val dist = kotlin.math.sqrt(
                    cameraToHit[0] * cameraToHit[0] +
                        cameraToHit[1] * cameraToHit[1] +
                        cameraToHit[2] * cameraToHit[2]
                )
                _wallDistance.value = dist
                _distanceSource.value = when (first.trackable) {
                    is com.google.ar.core.DepthPoint -> DistanceSource.DEPTH
                    is com.google.ar.core.Plane      -> DistanceSource.PLANE
                    else                              -> DistanceSource.RAYCAST
                }
            } else {
                _wallDistance.value = null
                _distanceSource.value = null
            }
        } catch (t: Throwable) {
            // ARCore can throw NotYetAvailableException early on.
            _wallDistance.value = null
            _distanceSource.value = null
        }

        // Snapshot vertical planes for the occlusion thread.
        if (session != null) {
            try {
                _verticalPlanes.value = session.getAllTrackables(Plane::class.java)
                    .asSequence()
                    .filter { it.trackingState == TrackingState.TRACKING }
                    .filter { it.type == Plane.Type.VERTICAL }
                    .map { plane ->
                        val poseMat = FloatArray(16)
                        plane.centerPose.toMatrix(poseMat, 0)
                        PlaneSnapshot(
                            transform = poseMat,
                            extentX = plane.extentX,
                            extentZ = plane.extentZ
                        )
                    }
                    .toList()
            } catch (t: Throwable) {
                // Trackable enumeration can fail under heavy GC. Safe
                // to ignore — last snapshot stays in place.
            }
        }

        // Snapshot depth image if the device supports it.
        if (_isDepthSupported.value) {
            try {
                frame.acquireDepthImage16Bits().use { img ->
                    val plane0 = img.planes[0]
                    val buffer = plane0.buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    _depthSnapshot.value = DepthSnapshot(
                        widthPx = img.width,
                        heightPx = img.height,
                        rowStrideBytes = plane0.rowStride,
                        pixels = bytes
                    )
                }
            } catch (t: Throwable) {
                // NotYetAvailableException is common on early frames;
                // also some devices report depth-supported but throw
                // NotYetAvailableException for the whole session.
                // Leave the previous snapshot in place — occlusion
                // gracefully degrades to plane-only.
            }
        }
    }

    /** Mark the session as no longer tracking — call from
     *  `onSessionPaused` / `onSessionFailed` so consumers can hide
     *  overlays without waiting for a tracking-state-change frame. */
    fun markUntracked() {
        _isTracking.value = false
    }

    /**
     * Project a world-space (ARCore session-frame) point into the
     * Android viewport. Mirrors iOS `projectToScreen`.
     *
     * Returns `null` when:
     *   * the camera isn't tracking yet,
     *   * the viewport is zero-sized,
     *   * or the point is behind the camera (would project to a
     *     non-positive `w`).
     */
    fun projectToScreen(world: FloatArray): Offset? {
        val view = _viewMatrix.value ?: return null
        val proj = _projectionMatrix.value ?: return null
        val vp = _viewportSize.value ?: return null
        if (vp.width <= 0 || vp.height <= 0) return null
        if (world.size < 3) return null

        // world (x, y, z, 1) → view space (column-major multiply).
        val viewSpace = multiplyMatVec4(view, world[0], world[1], world[2], 1f)
        val clip = multiplyMatVec4(proj, viewSpace[0], viewSpace[1], viewSpace[2], viewSpace[3])
        val w = clip[3]
        if (w <= 0f) return null  // behind camera

        // Clip → NDC (-1..1).
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        // NDC → viewport pixels. ARCore matches OpenGL convention:
        // +Y points up. Android viewports have +Y pointing down, so
        // we flip Y.
        val screenX = (ndcX + 1f) * 0.5f * vp.width
        val screenY = (1f - ndcY) * 0.5f * vp.height
        if (!screenX.isFinite() || !screenY.isFinite()) return null
        return Offset(screenX, screenY)
    }

    /**
     * Column-major 4×4 matrix times 4-vector. Returns a 4-element
     * `FloatArray`. Inline-allocated locally so this is GC-light for
     * something called once per visible marker per frame.
     */
    private fun multiplyMatVec4(m: FloatArray, x: Float, y: Float, z: Float, w: Float): FloatArray {
        // m is column-major: m[col*4 + row]
        val out = FloatArray(4)
        for (row in 0 until 4) {
            out[row] =
                m[0 * 4 + row] * x +
                m[1 * 4 + row] * y +
                m[2 * 4 + row] * z +
                m[3 * 4 + row] * w
        }
        return out
    }

    /** Tiny viewport size struct so we don't drag in `androidx.compose.ui.unit.IntSize`'s
     *  packed-long quirks. */
    data class IntSize(val width: Int, val height: Int)

    /**
     * Thread-safe snapshot of a detected vertical plane. Stores the
     * column-major world transform plus extents so the occlusion
     * thread can intersect rays against it without touching the
     * non-thread-safe `Plane` trackable.
     */
    data class PlaneSnapshot(
        val transform: FloatArray,
        val extentX: Float,
        val extentZ: Float
    )

    /**
     * Raw depth image copied out of the ARCore Image so it can be
     * read after the frame is released. ARCore returns depth in
     * millimetres as Big-endian U16 pixels in plane 0.
     */
    data class DepthSnapshot(
        val widthPx: Int,
        val heightPx: Int,
        val rowStrideBytes: Int,
        val pixels: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DepthSnapshot) return false
            return widthPx == other.widthPx &&
                heightPx == other.heightPx &&
                rowStrideBytes == other.rowStrideBytes &&
                pixels.contentEquals(other.pixels)
        }
        override fun hashCode(): Int {
            var result = widthPx
            result = 31 * result + heightPx
            result = 31 * result + rowStrideBytes
            result = 31 * result + pixels.contentHashCode()
            return result
        }
    }
}
