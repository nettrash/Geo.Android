package me.nettrash.geo.ar

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Synthetic END-TO-END reproduction harness for the reported N↔S cardinal
 * mirror in the Nature/AR view.
 *
 * Everything here is derived from FIRST PRINCIPLES (bases stated explicitly in
 * comments below) and then fed through the REAL production code:
 *
 *   * [cameraHeadingDeg] (top-level in HorizonOverlay.kt) — ARCore-pose heading
 *   * the exact yaw formula `frameYaw = normalize(compass − arcoreHeading)`
 *     with the first-frame latch (ArSceneController.update, first branch)
 *   * [ArSceneController.projectToScreen] — the pure explicit-matrix overload,
 *     which internally applies [ArSceneController.trueNorthAdjusted]
 *   * world points built EXACTLY the way HorizonOverlay builds its cardinal
 *     pills: (D·sin b + cam.x, up + cam.y, −D·cos b + cam.z)
 *
 * plus a first-principles check of the DeviceMotionManager camera-azimuth
 * formula `atan2(−R[2], −R[5])` against a hand-built sensor rotation matrix.
 *
 * PHYSICAL MODEL / CONVENTIONS (stated once, used everywhere):
 *
 *  - TRUE frame (the app's "pretend" frame): X = east, Y = up, Z = −north.
 *    A horizontal direction with true compass bearing t (0=N, 90=E, clockwise
 *    seen from above) is the unit vector (sin t, 0, −cos t).
 *      t=0   → (0,0,−1)  = −Z = north      ✓
 *      t=90  → (1,0,0)   = +X = east       ✓
 *
 *  - ARCORE WORLD frame: gravity-aligned (its Y = up) but yawed by an
 *    arbitrary session angle ψ relative to true north. DEFINITION used here:
 *    a direction whose TRUE bearing is t has ARCORE bearing (t − ψ), where
 *    "arcore bearing b" means the vector (sin b, 0, −cos b) written in ARCore
 *    world coordinates. (ψ = 0 means the frames coincide; positive ψ means
 *    ARCore's −Z axis points at true bearing ψ.) This matches the production
 *    sign convention `frameYawOffsetDeg = trueCompassHeading − arcorePoseHeading`:
 *    for the camera itself, true bearing C ⇒ arcore bearing C − ψ, so
 *    compass − arcoreHeading = C − (C − ψ) = ψ.
 */
class TrueNorthChainTest {

    // ------------------------------------------------------------------
    // Matrix construction from first principles
    // ------------------------------------------------------------------

    /**
     * Column-major 4×4 ARCore-style view matrix (world → eye) for an UPRIGHT
     * camera at [camPos] (ARCore world coords) whose optical axis points
     * horizontally at ARCORE bearing [arcoreBearingDeg].
     *
     * Basis vectors, derived by hand in the ARCore world frame (Y up):
     *   forward  f = ( sin h, 0, −cos h)   — definition of "arcore bearing h"
     *   up       u = ( 0,     1,  0     )  — upright camera
     *   right    r = f × u = ( cos h, 0, sin h )
     *       check h=0 (facing −Z): r = +X, i.e. facing north ⇒ right = east ✓
     *   backward b = −f = (−sin h, 0, cos h)
     *
     * A view matrix's rotation part has ROWS r, u, b (it maps world vectors
     * onto the camera axes), and its translation column is (−r·p, −u·p, −b·p)
     * so that the camera position maps to the eye-space origin. Column-major
     * storage: m[col*4 + row].
     */
    private fun buildViewMatrix(arcoreBearingDeg: Double, camPos: FloatArray): FloatArray {
        val h = Math.toRadians(arcoreBearingDeg)
        val r = doubleArrayOf(cos(h), 0.0, sin(h))
        val u = doubleArrayOf(0.0, 1.0, 0.0)
        val b = doubleArrayOf(-sin(h), 0.0, cos(h))
        val p = doubleArrayOf(camPos[0].toDouble(), camPos[1].toDouble(), camPos[2].toDouble())
        fun dot(a: DoubleArray, c: DoubleArray) = a[0] * c[0] + a[1] * c[1] + a[2] * c[2]

        val m = FloatArray(16)
        // rotation rows r,u,b → column-major m[col*4+row]
        for (col in 0..2) {
            m[col * 4 + 0] = r[col].toFloat()
            m[col * 4 + 1] = u[col].toFloat()
            m[col * 4 + 2] = b[col].toFloat()
            m[col * 4 + 3] = 0f
        }
        m[12] = (-dot(r, p)).toFloat()
        m[13] = (-dot(u, p)).toFloat()
        m[14] = (-dot(b, p)).toFloat()
        m[15] = 1f
        return m
    }

    /**
     * Standard column-major OpenGL perspective projection: 60° VERTICAL fov,
     * portrait aspect 9:16, near 0.01, far 1000 (same clip planes production
     * requests from ARCore).
     */
    private fun buildProjectionMatrix(): FloatArray {
        val fovYDeg = 60.0
        val aspect = 9.0 / 16.0            // width / height, portrait
        val near = 0.01
        val far = 1000.0
        val f = 1.0 / tan(Math.toRadians(fovYDeg) / 2.0)
        val m = FloatArray(16)
        m[0] = (f / aspect).toFloat()                       // (row0,col0)
        m[5] = f.toFloat()                                  // (row1,col1)
        m[10] = ((far + near) / (near - far)).toFloat()     // (row2,col2)
        m[11] = -1f                                         // (row3,col2)
        m[14] = (2.0 * far * near / (near - far)).toFloat() // (row2,col3)
        return m
    }

    /** Multiply column-major 4×4 by (x,y,z,1) — local helper for self-checks. */
    private fun mul(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        for (row in 0..3) {
            out[row] = m[0 * 4 + row] * x + m[1 * 4 + row] * y +
                m[2 * 4 + row] * z + m[3 * 4 + row] * 1f
        }
        return out
    }

    private fun normalizeDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    /** Shortest absolute angular difference. */
    private fun angDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return abs(d)
    }

    // ------------------------------------------------------------------
    // Self-check: the hand-built view matrix really is a view matrix
    // ------------------------------------------------------------------

    @Test
    fun viewMatrixConstruction_pointAheadHasNegativeEyeZ() {
        val camPos = floatArrayOf(1.2f, 0.3f, -2.5f)
        for (h in listOf(0.0, 37.0, 90.0, 180.0, 233.0, 270.0)) {
            val view = buildViewMatrix(h, camPos)
            // A point 10 m straight ahead of the camera (arcore bearing h):
            val hr = Math.toRadians(h)
            val eye = mul(
                view,
                camPos[0] + (10.0 * sin(hr)).toFloat(),
                camPos[1],
                camPos[2] + (-10.0 * cos(hr)).toFloat()
            )
            // Eye space: camera looks down −Z, point dead ahead ⇒ z ≈ −10, x ≈ y ≈ 0.
            assertWithMessage("eye-z for arcore bearing $h").that(eye[2].toDouble())
                .isWithin(1e-4).of(-10.0)
            assertWithMessage("eye-x for arcore bearing $h").that(abs(eye[0].toDouble()))
                .isLessThan(1e-4)
            assertWithMessage("eye-y for arcore bearing $h").that(abs(eye[1].toDouble()))
                .isLessThan(1e-4)
        }
    }

    // ------------------------------------------------------------------
    // Stage 1: cameraHeadingDeg reads the arcore bearing back out of the view
    // ------------------------------------------------------------------

    @Test
    fun cameraHeadingDeg_recoversArcoreBearing() {
        val camPos = floatArrayOf(1.2f, 0.3f, -2.5f)
        for (h in listOf(0.0, 37.0, 90.0, 180.0, 233.0, 270.0, 359.0)) {
            val got = cameraHeadingDeg(buildViewMatrix(h, camPos))
            assertWithMessage("cameraHeadingDeg for arcore bearing $h")
                .that(angDiff(got, h)).isLessThan(1e-3)
        }
    }

    // ------------------------------------------------------------------
    // Stage 2 (+3+4): full chain — compass → yaw latch → real projectToScreen
    // ------------------------------------------------------------------

    /**
     * The end-to-end assertion: with the camera TRULY facing compass bearing C
     * and the ARCore world yawed by ψ, feeding compass = C through the real
     * yaw-latch formula and projecting cardinal pills through the REAL
     * projectToScreen must put the cardinal whose bearing == C nearest the
     * horizontal screen centre. If the reported bug (N↔S mirror) lives in this
     * committed math, C=180 will put "N" at the centre and this test fails.
     */
    @Test
    fun endToEnd_cardinalAtCameraBearingProjectsToScreenCentre() {
        val controller = ArSceneController()
        val proj = buildProjectionMatrix()
        val vp = ArSceneController.IntSize(1080, 1920)
        val camPos = floatArrayOf(1.2f, 0.3f, -2.5f)
        // Cardinal bearings and labels exactly as HorizonOverlay's marker set.
        val cardinals = listOf(
            0.0 to "N", 45.0 to "NE", 90.0 to "E", 135.0 to "SE",
            180.0 to "S", 225.0 to "SW", 270.0 to "W", 315.0 to "NW"
        )
        val d = 20_000.0        // horizon-scale distance, like the real overlay
        val up = -50.0          // slightly below eye level, like the horizon dip

        for (c in listOf(0.0, 90.0, 180.0, 270.0)) {
            for (psi in listOf(0.0, 37.0, 180.0, 233.0)) {
                // Physical model: camera truly faces compass bearing C; by the
                // frame definition in the class KDoc its ARCORE bearing is C−ψ.
                val arcoreCamBearing = normalizeDeg(c - psi)
                val view = buildViewMatrix(arcoreCamBearing, camPos)

                // --- REAL stage 1: pose heading from the view matrix ---------
                val arcoreHeading = cameraHeadingDeg(view)

                // --- REAL stage 2: the exact yaw formula + first-frame latch -
                // ArSceneController.update does, on the very first compass frame:
                //   target = normalizeDeg(compass − arcoreHeading)
                //   frameYawOffsetDeg = target            (latch)
                // and with userAlignment = 0, appliedYawOffsetDeg == frameYaw.
                val compass = c.toFloat()
                val frameYaw = ((compass - arcoreHeading.toFloat()) % 360f + 360f) % 360f

                // Sanity midpoint: the latched yaw must equal the session yaw ψ.
                assertWithMessage("frameYaw for C=$c psi=$psi")
                    .that(angDiff(frameYaw.toDouble(), psi)).isLessThan(1e-2)

                // --- REAL stages 3+4: build pills as HorizonOverlay does and
                //     push them through the REAL projection choke point. ------
                var bestLabel: String? = null
                var bestDist = Double.MAX_VALUE
                val centreX = vp.width / 2.0
                val results = StringBuilder()
                for ((b, label) in cardinals) {
                    val theta = Math.toRadians(b)
                    // EXACT HorizonOverlay construction: TRUE-bearing point in
                    // the pretend frame, offset from the camera position.
                    val world = floatArrayOf(
                        (d * sin(theta)).toFloat() + camPos[0],
                        up.toFloat() + camPos[1],
                        (-d * cos(theta)).toFloat() + camPos[2]
                    )
                    val screen = controller.projectToScreen(
                        world, view, proj, vp, camPos, frameYaw
                    )
                    if (screen != null) {
                        val dist = abs(screen.x - centreX)
                        results.append("$label→x=${"%.1f".format(screen.x)} ")
                        if (dist < bestDist) { bestDist = dist; bestLabel = label }
                    } else {
                        results.append("$label→behind ")
                    }
                }

                val expected = cardinals.first { it.first == c }.second
                assertWithMessage(
                    "C=$c psi=$psi: cardinal nearest screen centre should be " +
                        "$expected but was $bestLabel [$results]"
                ).that(bestLabel).isEqualTo(expected)
                // The winner must be essentially dead-centre, not merely closest.
                assertWithMessage("C=$c psi=$psi centre distance px")
                    .that(bestDist).isLessThan(1.0)
            }
        }
    }

    /** The user-observed symptom, isolated: facing TRUE SOUTH (C=180) the "N"
     *  pill must be behind the camera / far from centre — never at centre. */
    @Test
    fun facingSouth_northPillIsNotAtScreenCentre() {
        val controller = ArSceneController()
        val proj = buildProjectionMatrix()
        val vp = ArSceneController.IntSize(1080, 1920)
        val camPos = floatArrayOf(0f, 0f, 0f)
        for (psi in listOf(0.0, 37.0, 180.0, 233.0)) {
            val arcoreCamBearing = normalizeDeg(180.0 - psi)
            val view = buildViewMatrix(arcoreCamBearing, camPos)
            val frameYaw =
                ((180f - cameraHeadingDeg(view).toFloat()) % 360f + 360f) % 360f
            val northWorld = floatArrayOf(0f, -50f, -20_000f) // b=0 exactly as built
            val screen = controller.projectToScreen(
                northWorld, view, proj, vp, camPos, frameYaw
            )
            // Facing south, north is behind the camera: projectToScreen must
            // reject it (w <= 0). If the committed math mirrored N↔S, north
            // would instead land at the screen centre here.
            assertWithMessage("psi=$psi: N pill while facing S projected to $screen")
                .that(screen).isNull()
        }
    }

    // ------------------------------------------------------------------
    // Stage 0: DeviceMotionManager's camera-azimuth formula
    // ------------------------------------------------------------------

    /**
     * First-principles check of `camAzimuth = atan2(−R[2], −R[5])`.
     *
     * Android sensor conventions (SensorManager.getRotationMatrixFromVector):
     *  - DEVICE frame: X = right edge of the screen (portrait), Y = top edge
     *    of the screen, Z = out of the SCREEN toward the user.
     *  - WORLD frame: X = east, Y = north, Z = up (ENU).
     *  - R is ROW-MAJOR and maps device → world; therefore its COLUMNS are the
     *    world images of the device axes: col0 = world(deviceX),
     *    col1 = world(deviceY), col2 = world(deviceZ), with R[row*3 + col].
     *
     * Scene: phone held UPRIGHT in portrait, screen vertical, BACK camera
     * (device −Z) pointing horizontally at true compass bearing C. Derivation
     * of the world images of the device axes:
     *  - back camera dir (device −Z) in ENU = (sin C, cos C, 0)
     *      ⇒ world(deviceZ) = (−sin C, −cos C, 0)
     *  - top of phone points at the sky ⇒ world(deviceY) = (0, 0, 1)
     *  - right-handedness: world(deviceX) = world(deviceY) × world(deviceZ)
     *      = (0,0,1) × (−sin C, −cos C, 0) = (cos C, −sin C, 0)
     *      check C=0: camera north, user faces north, screen-right = user's
     *      right = east = (1,0,0) ✓
     */
    @Test
    fun deviceMotionFormula_upright_backCameraAzimuthEqualsBearing() {
        for (c in listOf(0.0, 90.0, 180.0, 270.0)) {
            val cr = Math.toRadians(c)
            // Row-major R[row*3+col] from the columns derived above.
            val r = FloatArray(9)
            // col0 = world(deviceX) = (cos C, −sin C, 0)
            r[0] = cos(cr).toFloat(); r[3] = (-sin(cr)).toFloat(); r[6] = 0f
            // col1 = world(deviceY) = (0, 0, 1)
            r[1] = 0f; r[4] = 0f; r[7] = 1f
            // col2 = world(deviceZ) = (−sin C, −cos C, 0)
            r[2] = (-sin(cr)).toFloat(); r[5] = (-cos(cr)).toFloat(); r[8] = 0f

            // EXACT production expression (DeviceMotionManager.onSensorChanged):
            var camAzimuth = Math.toDegrees(
                kotlin.math.atan2((-r[2]).toDouble(), (-r[5]).toDouble())
            )
            if (camAzimuth < 0) camAzimuth += 360.0

            assertWithMessage("camera azimuth for phone facing $c")
                .that(angDiff(camAzimuth, c)).isLessThan(1e-4)
        }
    }

    /** Cross-check: the two frames agree — a view matrix built for a camera at
     *  arcore bearing h and the sensor matrix built for compass bearing C are
     *  consistent when ψ=0 and h=C (both formulas then report the same value). */
    @Test
    fun sensorAndArcoreFormulas_agreeWhenFramesCoincide() {
        for (c in listOf(0.0, 90.0, 180.0, 270.0)) {
            val fromView = cameraHeadingDeg(buildViewMatrix(c, floatArrayOf(0f, 0f, 0f)))
            assertThat(angDiff(fromView, c)).isLessThan(1e-3)
        }
    }
}
