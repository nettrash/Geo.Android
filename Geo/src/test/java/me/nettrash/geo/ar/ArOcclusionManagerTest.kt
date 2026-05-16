package me.nettrash.geo.ar

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests cover the pure-math paths in [ArOcclusionManager]: ray vs
 * vertical-plane intersection and U16 depth-image sampling. ARCore
 * trackables / depth-image acquisition aren't exercised here; those
 * paths are device-specific and tested manually.
 */
class ArOcclusionManagerTest {

    private val manager = ArOcclusionManager()

    // ─── isOccludedByPlanes ────────────────────────────────────────

    @Test fun noPlanesMeansNoOcclusion() {
        val occluded = manager.isOccludedByPlanes(
            cameraPos = floatArrayOf(0f, 0f, 0f),
            targetPos = floatArrayOf(0f, 0f, -5f),
            distance = 5f,
            planes = emptyList()
        )
        assertThat(occluded).isFalse()
    }

    @Test fun planeBetweenCameraAndTargetCountsAsOccluded() {
        // Wall 2 m in front of the camera, target 5 m. Wall is
        // 2 × 2 m so the ray (which goes straight ahead) hits dead
        // centre.
        val plane = wallPlaneAt(z = -2f, halfX = 1f, halfZ = 1f)
        val occluded = manager.isOccludedByPlanes(
            cameraPos = floatArrayOf(0f, 0f, 0f),
            targetPos = floatArrayOf(0f, 0f, -5f),
            distance = 5f,
            planes = listOf(plane)
        )
        assertThat(occluded).isTrue()
    }

    @Test fun planeBehindCameraDoesNotOcclude() {
        val plane = wallPlaneAt(z = 2f, halfX = 1f, halfZ = 1f)
        val occluded = manager.isOccludedByPlanes(
            cameraPos = floatArrayOf(0f, 0f, 0f),
            targetPos = floatArrayOf(0f, 0f, -5f),
            distance = 5f,
            planes = listOf(plane)
        )
        assertThat(occluded).isFalse()
    }

    @Test fun planeBeyondTargetDoesNotOcclude() {
        // Wall 10 m in front, target only 5 m → wall is past the
        // target so cannot occlude.
        val plane = wallPlaneAt(z = -10f, halfX = 1f, halfZ = 1f)
        val occluded = manager.isOccludedByPlanes(
            cameraPos = floatArrayOf(0f, 0f, 0f),
            targetPos = floatArrayOf(0f, 0f, -5f),
            distance = 5f,
            planes = listOf(plane)
        )
        assertThat(occluded).isFalse()
    }

    @Test fun rayPassesBesidePlaneEdge() {
        // Wall is 2 m in front but only ±0.5 m wide; the ray goes
        // through (1, 0, -5) which misses the wall horizontally.
        val plane = wallPlaneAt(z = -2f, halfX = 0.5f, halfZ = 0.5f)
        val occluded = manager.isOccludedByPlanes(
            cameraPos = floatArrayOf(0f, 0f, 0f),
            targetPos = floatArrayOf(1.5f, 0f, -5f),
            distance = kotlin.math.sqrt(1.5f * 1.5f + 25f),
            planes = listOf(plane)
        )
        assertThat(occluded).isFalse()
    }

    // ─── isOccludedByDepth ─────────────────────────────────────────

    @Test fun depthOcclusionFiresWhenSceneIsCloser() {
        // 100×100 viewport, 10×10 depth image. Marker is at
        // camera-space depth 5 m; the scene depth at the marker's
        // pixel is 2 m → should be occluded.
        val depth = depthSnapshotWithUniformMillimeters(
            widthPx = 10, heightPx = 10, valueMm = 2_000
        )
        val viewport = ArSceneController.IntSize(100, 100)
        val occluded = manager.isOccludedByDepth(
            screen = Offset(50f, 50f),
            viewport = viewport,
            cameraSpaceDepth = 5f,
            depth = depth
        )
        assertThat(occluded).isTrue()
    }

    @Test fun depthOcclusionStaysOffWhenSceneIsFarther() {
        // Scene depth 20 m, marker depth 5 m → not occluded.
        val depth = depthSnapshotWithUniformMillimeters(
            widthPx = 10, heightPx = 10, valueMm = 20_000
        )
        val viewport = ArSceneController.IntSize(100, 100)
        val occluded = manager.isOccludedByDepth(
            screen = Offset(50f, 50f),
            viewport = viewport,
            cameraSpaceDepth = 5f,
            depth = depth
        )
        assertThat(occluded).isFalse()
    }

    @Test fun depthOcclusionIgnoresUnmeasuredPixels() {
        // 0 depth means "unmeasured" — should not occlude.
        val depth = depthSnapshotWithUniformMillimeters(
            widthPx = 10, heightPx = 10, valueMm = 0
        )
        val viewport = ArSceneController.IntSize(100, 100)
        val occluded = manager.isOccludedByDepth(
            screen = Offset(50f, 50f),
            viewport = viewport,
            cameraSpaceDepth = 5f,
            depth = depth
        )
        assertThat(occluded).isFalse()
    }

    // ─── Helpers ──────────────────────────────────────────────────

    /**
     * Construct a column-major 4×4 transform for a vertical plane
     * standing parallel to the XY plane at the given Z. Y axis (the
     * plane's "normal" in ARCore's plane local frame) points along
     * world −Z so the plane faces the camera.
     */
    private fun wallPlaneAt(
        z: Float,
        halfX: Float,
        halfZ: Float
    ): ArSceneController.PlaneSnapshot {
        // Local axes:
        //   X' = world +X
        //   Y' = world −Z (the "up" in plane-local terms is the
        //                  outward normal)
        //   Z' = world −Y
        // → transform columns 0..3:
        //   col0 = (1, 0, 0, 0)
        //   col1 = (0, 0, -1, 0)
        //   col2 = (0, -1, 0, 0)
        //   col3 = (0, 0, z, 1)
        val mat = floatArrayOf(
            1f, 0f, 0f, 0f,    // col 0
            0f, 0f, -1f, 0f,   // col 1 (normal: world −Z)
            0f, -1f, 0f, 0f,   // col 2
            0f, 0f, z, 1f      // col 3 (centre)
        )
        return ArSceneController.PlaneSnapshot(
            transform = mat,
            extentX = halfX * 2,
            extentZ = halfZ * 2
        )
    }

    private fun depthSnapshotWithUniformMillimeters(
        widthPx: Int,
        heightPx: Int,
        valueMm: Int
    ): ArSceneController.DepthSnapshot {
        val rowStride = widthPx * 2
        val bytes = ByteArray(rowStride * heightPx)
        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                val offset = y * rowStride + x * 2
                bytes[offset] = (valueMm and 0xFF).toByte()
                bytes[offset + 1] = ((valueMm ushr 8) and 0xFF).toByte()
            }
        }
        return ArSceneController.DepthSnapshot(
            widthPx = widthPx,
            heightPx = heightPx,
            rowStrideBytes = rowStride,
            pixels = bytes
        )
    }
}
