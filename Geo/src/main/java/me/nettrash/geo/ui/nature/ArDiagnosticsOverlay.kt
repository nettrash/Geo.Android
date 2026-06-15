package me.nettrash.geo.ui.nature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.ar.ArOcclusionManager
import me.nettrash.geo.ar.ArSceneController
import me.nettrash.geo.ar.SkylineCalculator
import me.nettrash.geo.ar.cameraHeadingDeg
import java.util.Locale

/**
 * Diagnostic overlay that surfaces low-level AR state. Hidden by
 * default; the user long-presses the AR top bar to reveal it (see
 * `NatureScreen.ArScene`). Tap anywhere on the panel to dismiss.
 *
 * Designed to be the first thing to look at when AR misbehaves in
 * the wild — tracking, plane counts, depth flag, occluded marker
 * count, and the inputs to the outdoor heuristic are all here.
 */
@Composable
fun ArDiagnosticsOverlay(
    controller: ArSceneController,
    occlusion: ArOcclusionManager,
    skyline: SkylineCalculator,
    peakCount: Int,
    historyCount: Int,
    locationAccuracy: Float?,
    onDismiss: () -> Unit
) {
    val isTracking by controller.isTracking.collectAsState()
    val viewport by controller.viewportSize.collectAsState()
    val cameraPos by controller.cameraPosition.collectAsState()
    val viewMatrix by controller.viewMatrix.collectAsState()
    val planes by controller.verticalPlanes.collectAsState()
    val depthSnapshot by controller.depthSnapshot.collectAsState()
    val isDepthSupported by controller.isDepthSupported.collectAsState()
    val wallDistance by controller.wallDistance.collectAsState()
    val distanceSource by controller.distanceSource.collectAsState()
    val occluded by occlusion.occludedIds.collectAsState()
    val isOutdoor by occlusion.isOutdoor.collectAsState()
    val isSkylineComputing by skyline.isComputing.collectAsState()
    val skylineSamples by skyline.samples.collectAsState()

    // Scrim — tap to dismiss.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101010))
                .padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp))
        ) {
            Header("AR DIAGNOSTICS")
            Spacer(Modifier.height(8.dp))

            Section("session")
            Row2("tracking", if (isTracking) "yes" else "no",
                ok = isTracking)
            Row2("viewport",
                viewport?.let { "${it.width}×${it.height}" } ?: "—")
            Row2("camera (m)",
                cameraPos?.let {
                    String.format(Locale.US, "%.1f, %.1f, %.1f", it[0], it[1], it[2])
                } ?: "—")

            Spacer(Modifier.height(6.dp))
            Section("heading (true-north align)")
            val arPose = viewMatrix?.let { cameraHeadingDeg(it) }
            val northOffset = controller.frameYawOffsetDeg
            // The ARCore pose heading (frame-relative), the compass-derived
            // correction, and the resulting TRUE heading the overlay is drawn at.
            // Point at a known direction: "corrected" should match a real compass.
            Row2("ar pose", arPose?.let { String.format(Locale.US, "%.0f°", it) } ?: "—")
            Row2("north offset", String.format(Locale.US, "%+.0f°", northOffset))
            Row2("corrected (true)",
                arPose?.let {
                    String.format(Locale.US, "%.0f°", (((it + northOffset) % 360 + 360) % 360))
                } ?: "—")

            Spacer(Modifier.height(6.dp))
            Section("perception")
            Row2("vertical planes", planes.size.toString(),
                ok = planes.isNotEmpty())
            Row2("depth enabled",
                if (isDepthSupported) "yes" else "no",
                ok = isDepthSupported)
            Row2("depth frame",
                depthSnapshot?.let { "${it.widthPx}×${it.heightPx}" } ?: "—")
            Row2("wall dist",
                wallDistance?.let { String.format(Locale.US, "%.2f m", it) } ?: "—")
            Row2("distance src", distanceSource?.name?.lowercase() ?: "—")

            Spacer(Modifier.height(6.dp))
            Section("occlusion")
            Row2("outdoor heuristic", if (isOutdoor) "yes" else "no",
                ok = !isOutdoor)
            Row2("occluded markers", occluded.size.toString())

            Spacer(Modifier.height(6.dp))
            Section("scene")
            Row2("peaks", peakCount.toString())
            Row2("history points", historyCount.toString())
            Row2("gps accuracy",
                locationAccuracy?.let { String.format(Locale.US, "%.1f m", it) } ?: "—")

            Spacer(Modifier.height(6.dp))
            Section("skyline")
            Row2("computing", if (isSkylineComputing) "yes" else "no")
            Row2("samples", skylineSamples.size.toString(),
                ok = skylineSamples.isNotEmpty())

            Spacer(Modifier.height(12.dp))
            Text(
                "tap to dismiss",
                color = Color.Gray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        color = Color(0xFFFF9800),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        color = Color(0xFF80DEEA),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun Row2(label: String, value: String, ok: Boolean? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color(0xFF888888),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 8.dp)
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = when (ok) {
                true  -> Color(0xFF66BB6A)
                false -> Color(0xFFE57373)
                null  -> Color.White
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
