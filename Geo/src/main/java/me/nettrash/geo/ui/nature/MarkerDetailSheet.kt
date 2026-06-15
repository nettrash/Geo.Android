package me.nettrash.geo.ui.nature

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.R
import me.nettrash.geo.data.model.ARHistoryPoint
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.util.GeoCalculations
import java.text.SimpleDateFormat
import java.util.Locale

/** A marker the user tapped in the AR Nature view. Drives [MarkerDetailSheet]. */
sealed interface ArMarkerSelection {
    data class Peak(val peak: NearbyPeak) : ArMarkerSelection
    data class History(val point: ARHistoryPoint) : ArMarkerSelection
}

/**
 * Bottom-sheet detail card shown when an AR peak / history marker is tapped —
 * a richer rendering of the same fields the on-screen marker already shows.
 * Mirrors iOS `MarkerDetailSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerDetailSheet(selection: ArMarkerSelection, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1A1A1A)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            when (selection) {
                is ArMarkerSelection.Peak -> PeakDetail(selection.peak)
                is ArMarkerSelection.History -> HistoryDetail(selection.point)
            }
        }
    }
}

@Composable
private fun PeakDetail(peak: NearbyPeak) {
    Text(
        peak.name,
        color = Color(0xFFFF9800),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    DetailRow(stringResource(R.string.field_altitude), altitude(peak.altitude))
    DetailRow(stringResource(R.string.field_distance), distance(peak.distance))
    DetailRow(stringResource(R.string.field_bearing), bearing(peak.bearing))
    DetailRow(stringResource(R.string.field_coordinates), coords(peak.latitude, peak.longitude))
    DirectionsButton(peak.latitude, peak.longitude)
}

@Composable
private fun HistoryDetail(point: ARHistoryPoint) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Text(
        stringResource(R.string.ar_history_point),
        color = Color.Cyan,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    DetailRow(stringResource(R.string.ar_recorded), fmt.format(point.date))
    DetailRow(stringResource(R.string.ar_gps_altitude), altitude(point.gpsAltitude))
    if (point.barometerAltitude > 0) {
        DetailRow(stringResource(R.string.ar_bar_altitude), altitude(point.barometerAltitude))
    }
    if (point.pressure > 0) {
        DetailRow(stringResource(R.string.field_pressure), String.format(Locale.US, "%.1f kPa", point.pressure))
    }
    DetailRow(stringResource(R.string.field_velocity), String.format(Locale.US, "%.1f m/s", maxOf(0.0, point.speed)))
    DetailRow(stringResource(R.string.field_distance), distance(point.distance))
    DetailRow(stringResource(R.string.field_bearing), bearing(point.bearing))
    DetailRow(stringResource(R.string.field_coordinates), coords(point.latitude, point.longitude))
    DirectionsButton(point.latitude, point.longitude)
}

/** Opens the device's default maps app at the marker. Uses a generic `geo:`
 *  URI (no package) wrapped in runCatching so a Maps-less device can't crash —
 *  matches the Directions fix used on the Info cards. */
@Composable
private fun DirectionsButton(lat: Double, lon: Double) {
    val context = LocalContext.current
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))
                )
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(stringResource(R.string.action_directions), color = Color.White)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun altitude(m: Double): String =
    if (m >= 1000) String.format(Locale.US, "%.2f km", m / 1000) else String.format(Locale.US, "%.0f m", m)

private fun distance(m: Double): String =
    if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000) else String.format(Locale.US, "%.0f m", m)

private fun bearing(deg: Double): String =
    String.format(Locale.US, "%.0f° %s", deg, GeoCalculations.cardinalDirection(deg))

private fun coords(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.5f, %.5f", lat, lon)
