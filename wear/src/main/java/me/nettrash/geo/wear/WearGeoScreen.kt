package me.nettrash.geo.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import java.util.Locale

/**
 * Watch face for the Geo Watch app — mirrors iOS
 * `Geo Watch App/ContentView.swift`.
 *
 * Layout:
 *   • Header line: "Geo" + "from phone" indicator when inbound is fresh
 *   • Sparkline-style altitude history graph (20 samples)
 *   • Vertical rotated "Barometer" label
 *     – Pressure (kPa, mmHg, atm) — 4-decimal precision
 *   • Vertical rotated "Altitude" label
 *     – Altitude (m) — 0-decimal precision
 *     – % Everest
 */
@Composable
fun WearGeoScreen(
    pressureKpa: Double,
    altitudeMeters: Double,
    everestRatio: Double,
    altitudeHistory: List<Double>,
    inbound: WearInformationToken?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Sparkline behind the readings.
        if (altitudeHistory.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 30.dp)
            ) {
                drawSparkline(altitudeHistory)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Header(hasInbound = inbound != null)
            Spacer(Modifier.height(6.dp))
            BarometerSection(pressureKpa)
            Spacer(Modifier.height(6.dp))
            AltitudeSection(altitudeMeters, everestRatio)
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
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                "·",
                color = Color(0xFF80DEEA),
                fontSize = 11.sp
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                "phone",
                color = Color(0xFF80DEEA),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun BarometerSection(pressureKpa: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Barometer",
            color = Color(0xFF888888),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.rotate(-90f)
        )
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            MonoLine("${String.format(Locale.US, "%.4f", pressureKpa)} kPa")
            MonoLine("${String.format(Locale.US, "%.4f", pressureKpa * 7.50062)} mm Hg")
            MonoLine("${String.format(Locale.US, "%.4f", pressureKpa / 101.325)} atm")
        }
    }
}

@Composable
private fun AltitudeSection(altitudeMeters: Double, everestRatio: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Altitude",
            color = Color(0xFF888888),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.rotate(-90f)
        )
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            MonoLine("${String.format(Locale.US, "%.0f", altitudeMeters)} m")
            MonoLine("${String.format(Locale.US, "%.2f", everestRatio * 100)} % Everest")
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace
    )
}

/**
 * Tiny sparkline renderer — minimum-altitude on the left, latest on
 * the right, autoscaled to history extent. Lets the Watch user see
 * a 20-sample trend at a glance without needing the iOS Graph code.
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
        color = Color(0xFF80DEEA).copy(alpha = 0.4f),
        style = Stroke(width = 1.5f)
    )
}
