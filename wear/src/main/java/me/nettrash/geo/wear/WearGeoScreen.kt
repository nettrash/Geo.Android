package me.nettrash.geo.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import java.util.Locale

/**
 * Watch face for the Geo Watch app — round-display safe.
 *
 * The previous layout (rotated "Barometer" / "Altitude" side labels
 * + edge-aligned monospace columns) was clipped by the bezel on
 * round Wear OS faces, because none of it accounted for the
 * inscribed-square safe area.
 *
 * This redesign centres everything horizontally, pads to ~15% on
 * each side (the round inscription margin), and stacks vertically:
 *
 *   GEO            (orange brand mark)
 *   ────────────   (cyan accent line)
 *   3 421 m        (altitude — the headline value)
 *   95.40 kPa      (pressure)
 *   38.7% Everest  (Everest ratio)
 *   ╱╲╱╲╱╱╲╱       (20-sample altitude sparkline)
 *
 * Plus a small `· phone` badge in the top-right corner of the safe
 * area when inbound data is available from the paired iPhone.
 */
@Composable
fun WearGeoScreen(
    pressureKpa: Double,
    altitudeMeters: Double,
    everestRatio: Double,
    altitudeHistory: List<Double>,
    inbound: WearInformationToken?
) {
    val config = LocalConfiguration.current
    val isRound = config.isScreenRound

    // Round watches: inscribe content inside a square so neither
    // end of a wide text line crosses the bezel arc. 15% on each
    // side mirrors the (1 − √2/2)/2 ≈ 14.6% inscription margin,
    // rounded up.
    val sideFraction = if (isRound) 0.15f else 0.06f
    val verticalFraction = if (isRound) 0.16f else 0.08f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val sidePad = (maxWidth * sideFraction)
        val topPad = (maxHeight * verticalFraction)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    PaddingValues(
                        start = sidePad,
                        end = sidePad,
                        top = topPad,
                        bottom = topPad
                    )
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Header(hasInbound = inbound != null)
            Spacer(Modifier.height(4.dp))

            // Altitude headline.
            Text(
                text = String.format(Locale.US, "%.0f m", altitudeMeters),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // Secondary readings.
            Text(
                text = String.format(Locale.US, "%.2f kPa", pressureKpa),
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = String.format(Locale.US, "%.1f%% Everest", everestRatio * 100),
                color = Color(0xFFBDBDBD),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            // Sparkline — push to the bottom of the safe area.
            Spacer(Modifier.weight(1f))
            if (altitudeHistory.size >= 2) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                ) {
                    drawSparkline(altitudeHistory)
                }
            }
        }
    }
}

@Composable
private fun Header(hasInbound: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "GEO",
            color = Color(0xFFFF9800),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        if (hasInbound) {
            Spacer(Modifier.width(6.dp))
            // Small filled dot indicates we've received an inbound
            // snapshot from the paired phone in this session.
            Box(
                modifier = Modifier
                    .background(Color(0xFF80DEEA), shape = CircleShape)
                    .width(5.dp)
                    .height(5.dp)
            )
        }
    }
}

/**
 * Tiny sparkline renderer — autoscaled to history extent.
 * Drawn cyan with low alpha so it sits behind the numbers
 * unobtrusively. 20 samples wide.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkline(
    history: List<Double>
) {
    if (history.size < 2) return
    val min = history.min()
    val max = history.max()
    val range = (max - min).takeIf { it > 0.001 } ?: 1.0

    val step = size.width / (history.size - 1).coerceAtLeast(1)
    val path = Path().apply {
        history.forEachIndexed { i, v ->
            val x = i * step
            val y = (1f - ((v - min) / range).toFloat()) * size.height
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(
        path = path,
        color = Color(0xFF80DEEA).copy(alpha = 0.65f),
        style = Stroke(width = 1.5f)
    )
}
