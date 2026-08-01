package me.nettrash.geo.ar

import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

    /** Latest world→view matrix (4×4, column-major — ARCore's getViewMatrix
     *  output) or null until first frame. */
    private val _viewMatrix = MutableStateFlow<FloatArray?>(null)
    val viewMatrix: StateFlow<FloatArray?> = _viewMatrix.asStateFlow()

    /** Latest projection matrix (4×4, column-major — ARCore's
     *  getProjectionMatrix output). */
    private val _projectionMatrix = MutableStateFlow<FloatArray?>(null)
    val projectionMatrix: StateFlow<FloatArray?> = _projectionMatrix.asStateFlow()

    /** Camera world-position (Vec3) — ARCore's session origin. */
    private val _cameraPosition = MutableStateFlow<FloatArray?>(null)
    val cameraPosition: StateFlow<FloatArray?> = _cameraPosition.asStateFlow()

    /** Current viewport (px). */
    private val _viewportSize = MutableStateFlow<IntSize?>(null)
    val viewportSize: StateFlow<IntSize?> = _viewportSize.asStateFlow()

    // ---- True-north correction ---------------------------------------------
    // ARCore (no Geospatial) aligns its world frame to GRAVITY only — its yaw is
    // wherever the device faced at session start, NOT true north. iOS gets true
    // north for free from ARKit `gravityAndHeading`. So we measure the offset
    // between the device's true compass heading and the heading implied by the
    // ARCore pose, and rotate every projected point by it in [projectToScreen].
    // Sign convention: frameYawOffsetDeg = trueCompassHeading − arcorePoseHeading
    // (both 0=N, clockwise). If the overlay ends up rotated the WRONG way on a
    // device, negate this one value (and the rotation in projectToScreen).

    /** Smoothed true-north yaw correction (deg). Read by the overlay/hit-test to
     *  shift their bearing windows to true north; applied to every point in
     *  [projectToScreen]. */
    @Volatile
    var frameYawOffsetDeg: Float = 0f
        private set

    /** Latest device TRUE compass heading (deg, 0=N), pushed from the UI. NaN
     *  until the first reading — until then no correction is applied. */
    @Volatile
    private var compassTrueHeadingDeg: Float = Float.NaN
    private var yawOffsetInitialized = false

    /** Frames since the offset was first set. Used to converge FAST initially
     *  (lock onto true north within ~1 s of magnetometer averaging) and then
     *  HOLD, so the cardinal labels / skyline / peaks stay anchored to ARCore's
     *  stable tracking instead of sliding around as the compass wanders. */
    private var yawConvergeFrames = 0

    /** Frames of fast initial convergence before switching to hold-steady. */
    private val yawConvergeFrames0 = 90

    /** Lerp toward the compass-derived target during initial convergence. */
    private val yawConvergeLerp = 0.1f

    /** Steady-state lerp once converged — gentle, so it counters genuine ARCore
     *  yaw drift without chasing the magnetometer frame to frame. */
    private val yawHoldLerp = 0.02f

    /** Within this many degrees the steady-state offset is held FIXED. ARCore
     *  tracks rotation smoothly and drift-free over short spans, so a small
     *  per-orientation compass error (soft-iron, tilt) must NOT be allowed to
     *  drag the overlay — otherwise "E" slides off true east as the user pans.
     *  Only a larger sustained discrepancy (real ARCore yaw drift) is corrected. */
    private val yawHoldDeadbandDeg = 6f

    /** Push the device's true (declination-corrected) compass heading so the
     *  per-frame [update] can derive the ARCore-frame → true-north offset.
     *  Pass NaN while the compass is unusable (no sample yet, or accuracy
     *  LOW/UNRELIABLE under magnetic interference) — [update] then HOLDS the
     *  current offset instead of latching a corrupted one. */
    fun setCompassTrueHeading(deg: Float) { compassTrueHeadingDeg = deg }

    /** Set when compass accuracy recovers after a spell of magnetic
     *  interference; consumed by [update] on the next tracked frame with a
     *  valid compass. `@Volatile`: written on the UI thread, read on the AR
     *  frame thread. */
    @Volatile
    private var reconvergeRequested = false

    /** Request fast re-convergence (keeping the current offset as the starting
     *  point) because the compass just recovered from a bad spell. [update]
     *  honours it ONLY if the recovered compass actually disagrees with the
     *  held offset by more than the hold deadband — so a session that latched
     *  garbage during interference re-locks within ~1 s, while a session whose
     *  held offset was fine keeps its converged hold (and the pan stability
     *  the deadband exists for) even if accuracy flaps at the gate boundary. */
    fun reconverge() { reconvergeRequested = true }

    // ---- Manual compass alignment (user knob) --------------------------------

    /** Manual compass-alignment offset (deg), set by the user's horizontal pan
     *  in NatureScreen when the automatic compass heading is visibly off (a
     *  typical magnetometer error is 5–15°) and the drawn panorama doesn't line
     *  up with the real one. Applied inside the projection choke point (see
     *  [appliedYawOffsetDeg]) so everything — skyline, cardinal labels, welded
     *  pills, AR markers, occlusion targets, tap hit-tests, share render —
     *  shifts coherently with one value.
     *
     *  SIGN CONVENTION (mirrors iOS `ARSessionManager.headingAlignmentDeg`):
     *  positive rotates all drawn content CLOCKWISE in compass bearing (a
     *  point drawn at bearing θ renders where θ + offset would) — because
     *  screen-right corresponds to increasing azimuth relative to the camera,
     *  a POSITIVE offset moves the overlay RIGHT on screen, matching a
     *  rightward drag. Consumers that window content by camera heading must
     *  compensate: the true bearing at the screen centre is
     *  (cameraHeading − offset) — which is exactly what
     *  `cameraHeadingDeg(view) + appliedYawOffsetDeg` yields.
     *
     *  Session-only by design (the controller is remembered per AR
     *  composition, never persisted): compass error is different every
     *  session. */
    private val _userAlignmentDeg = MutableStateFlow(0f)
    val userAlignmentDeg: StateFlow<Float> = _userAlignmentDeg.asStateFlow()

    fun setUserAlignment(deg: Float) { _userAlignmentDeg.value = deg }

    /** The total yaw rotation the projection choke point applies:
     *  the automatic true-north correction MINUS the manual alignment.
     *
     *  Derivation of the minus (this composes with the historic true-north
     *  fix — do not flip it casually): [trueNorthAdjusted]'s rotation maps a
     *  point built at compass bearing β to bearing (β − yaw) in the ARCore
     *  frame; with yaw = [frameYawOffsetDeg] (= true − arcore) that lands
     *  true-bearing content at its correct ARCore direction. The manual knob
     *  wants content at β to render where (β + offset) would — iOS's
     *  `Geometry.rotateENU(clockwiseDegrees: offset)` — so the target ARCore
     *  bearing becomes (β + offset − frameYaw) = β − (frameYaw − offset),
     *  i.e. the SAME rotation with yaw = frameYawOffsetDeg − userAlignmentDeg.
     *  Check: positive offset ⇒ content at larger bearings ⇒ overlay moves
     *  RIGHT on screen, following a rightward drag. */
    val appliedYawOffsetDeg: Float
        get() = frameYawOffsetDeg - _userAlignmentDeg.value

    /** True when ARCore reports `TrackingState.TRACKING`. */
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    /**
     * Bumped once per ARCore frame. Overlays collect this to force a
     * recomposition every frame, so their projected content tracks the camera.
     *
     * Without it they'd only recompose when some *other* observed state changed:
     * the camera matrices are read through `.value` (not collected), because
     * collecting a `FloatArray` StateFlow re-emits on every new array anyway.
     * The Nature overlays used to get their per-frame refresh by accident, from
     * the diagnostic flows (wall distance, occluded ids) that ticked constantly;
     * when those were deleted the markers started updating only at sensor pace,
     * which read as markers jumping and blinking. This is the explicit version of
     * that refresh, and mirrors iOS `ARSessionManager.frameTick`.
     */
    private val _frameTick = MutableStateFlow(0L)
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    /**
     * How long `isTracking` stays true after ARCore stops reporting TRACKING.
     *
     * ARCore drops to PAUSED whenever it briefly loses visual features — which,
     * on a tab whose whole job is pointing at distant mountains and open sky, is
     * common and usually lasts only a few frames. `isTracking` is the single
     * switch that hides the horizon line, the cardinal markers AND every peak
     * marker, so reacting to each blip made the entire overlay flicker in and
     * out. Holding it briefly keeps the overlay drawn at the last good pose (the
     * matrices are left untouched while untracked) instead of blanking it.
     */
    private val trackingGraceMs = 800L
    private var lastTrackedMs = 0L


    /**
     * Called by the ARSceneView frame listener every ~16 ms. Cheap
     * — pulls a handful of matrices off ARCore and pushes them into
     * the StateFlows. Skips quietly when the camera isn't tracking
     * (matrices would be garbage anyway).
     */
    fun update(frame: Frame, viewportWidthPx: Int, viewportHeightPx: Int) {
        val cam = frame.camera
        _viewportSize.value = IntSize(viewportWidthPx, viewportHeightPx)

        val tracking = cam.trackingState == TrackingState.TRACKING
        val nowMs0 = System.currentTimeMillis()
        if (tracking) {
            lastTrackedMs = nowMs0
            _isTracking.value = true
        } else {
            // Hold the last good pose through a brief tracking blip rather than
            // blanking the whole overlay — see `trackingGraceMs`. The matrices
            // below are deliberately NOT overwritten while untracked, so the
            // overlay keeps drawing at the last pose we trusted.
            if (nowMs0 - lastTrackedMs > trackingGraceMs) _isTracking.value = false
            // Still tick so overlays re-render (and settle) during the grace.
            _frameTick.value += 1
            return
        }

        // One tick per tracked frame — the overlays' per-frame refresh signal.
        _frameTick.value += 1

        // 4×4 matrices, column-major in OpenGL — but ARCore's
        // getViewMatrix / getProjectionMatrix already returns column
        // major as a FloatArray(16). We store the raw column-major
        // form and the projection helper does the math against it
        // directly, so no transpose needed here.
        val view = FloatArray(16)
        cam.getViewMatrix(view, 0)
        _viewMatrix.value = view

        val proj = FloatArray(16)
        // zNear/zFar match iOS (ARSessionManager: 0.01 / 1000) so both ports
        // clip very-near markers identically. Near/far don't affect the x/y NDC
        // of projected points, so this is parity hygiene, not a visual change.
        cam.getProjectionMatrix(proj, 0, 0.01f, 1_000f)
        _projectionMatrix.value = proj

        val pos = FloatArray(3)
        cam.pose.getTranslation(pos, 0)
        _cameraPosition.value = pos

        // Derive the true-north yaw correction from the device compass vs the
        // ARCore pose heading. The two track the same physical rotation, so their
        // difference is ~constant for the session; smooth it to reject magnetic
        // jitter without lagging real drift.
        val compass = compassTrueHeadingDeg
        if (!compass.isNaN()) {
            val arcoreHeading = cameraHeadingDeg(view).toFloat()
            val target = normalizeDeg(compass - arcoreHeading)
            // A requested post-recovery re-lock is honoured only when the
            // fresh target genuinely disagrees with the held offset (see
            // [reconverge]); consumed here, where a valid target exists.
            if (reconvergeRequested) {
                reconvergeRequested = false
                if (yawOffsetInitialized &&
                    abs(shortestDeg(target - frameYawOffsetDeg)) > yawHoldDeadbandDeg
                ) {
                    yawConvergeFrames = 0
                }
            }
            when {
                !yawOffsetInitialized -> {
                    yawOffsetInitialized = true
                    yawConvergeFrames = 0
                    frameYawOffsetDeg = target
                }
                yawConvergeFrames < yawConvergeFrames0 -> {
                    // Fast initial lock: average out magnetometer noise for ~1 s.
                    yawConvergeFrames++
                    frameYawOffsetDeg = normalizeDeg(
                        frameYawOffsetDeg + shortestDeg(target - frameYawOffsetDeg) * yawConvergeLerp
                    )
                }
                else -> {
                    // Converged → HOLD. The offset stays put unless the compass
                    // disagrees by more than the deadband (a real ARCore yaw
                    // drift), so the overlay rides ARCore's smooth, drift-free
                    // tracking and the cardinal labels don't wander as the user
                    // pans. (iOS gets a stable true-north frame from ARKit's
                    // gravityAndHeading; ARCore has no equivalent, so we lock the
                    // bolted-on offset instead of tracking the compass live.)
                    val signedDiff = shortestDeg(target - frameYawOffsetDeg)
                    if (abs(signedDiff) > yawHoldDeadbandDeg) {
                        frameYawOffsetDeg = normalizeDeg(frameYawOffsetDeg + signedDiff * yawHoldLerp)
                    }
                }
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
     * Apply the true-north yaw correction to a world point: rotate it about the
     * camera position around +Y by [frameYawOffsetDeg], for an (X=east, Z=−north)
     * frame (`x' = x·cosδ + z·sinδ ; z' = −x·sinδ + z·cosδ`). This is the SAME
     * transform [projectToScreen] applies before the view/projection multiply,
     * exposed so the occlusion depth path can derive a camera-space depth from
     * the identical rotated point — otherwise the screen pixel it samples and the
     * marker depth it compares describe two different world directions. Returns
     * the input array unchanged when no offset is set or the camera pose isn't
     * known yet.
     */
    fun trueNorthAdjusted(world: FloatArray): FloatArray =
        trueNorthAdjusted(world, _cameraPosition.value, appliedYawOffsetDeg)

    /** Pure variant taking an explicit camera position + yaw offset, so an
     *  off-main caller can snapshot ONE consistent frame and pass it in rather
     *  than re-reading the per-frame StateFlows (which could tear). */
    fun trueNorthAdjusted(world: FloatArray, camPos: FloatArray?, yawDeg: Float): FloatArray {
        if (yawDeg == 0f || camPos == null || world.size < 3) return world
        val ox = world[0] - camPos[0]
        val oz = world[2] - camPos[2]
        val a = Math.toRadians(yawDeg.toDouble())
        val ca = cos(a)
        val sa = sin(a)
        return floatArrayOf(
            camPos[0] + (ox * ca + oz * sa).toFloat(),
            world[1],
            camPos[2] + (-ox * sa + oz * ca).toFloat()
        )
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
        // Composed yaw: automatic true-north correction + the manual
        // compass-alignment knob (see [appliedYawOffsetDeg] for the sign
        // derivation) — so every consumer of this choke point shifts
        // coherently when the user drags the panorama into alignment.
        return projectToScreen(world, view, proj, vp, _cameraPosition.value, appliedYawOffsetDeg)
    }

    /**
     * Project using an EXPLICITLY captured matrix set. The off-main occlusion
     * worker snapshots one consistent frame (view/proj/viewport/camPos/yaw) and
     * passes it here, so its projection can't tear across the per-frame
     * StateFlow writes happening on the main thread. The no-arg overload above
     * just reads the current `.value`s and delegates, so the main-thread render
     * path is behaviourally unchanged.
     */
    fun projectToScreen(
        world: FloatArray,
        view: FloatArray,
        proj: FloatArray,
        vp: IntSize,
        camPos: FloatArray?,
        yawDeg: Float
    ): Offset? {
        if (vp.width <= 0 || vp.height <= 0) return null
        if (world.size < 3) return null

        // Correct ARCore's non-north-aligned world frame by rotating the point
        // about the camera by the measured yaw offset (see [trueNorthAdjusted]),
        // so a point built at its TRUE bearing lands at the true real-world
        // direction.
        val adj = trueNorthAdjusted(world, camPos, yawDeg)

        // world (x, y, z, 1) → view space (column-major multiply).
        val viewSpace = multiplyMatVec4(view, adj[0], adj[1], adj[2], 1f)
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

    /** Wrap an angle to [0, 360). */
    private fun normalizeDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    /** Shortest signed difference between two angles, in (−180, 180]. */
    private fun shortestDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Tiny viewport size struct so we don't drag in `androidx.compose.ui.unit.IntSize`'s
     *  packed-long quirks. */
    data class IntSize(val width: Int, val height: Int)

}
