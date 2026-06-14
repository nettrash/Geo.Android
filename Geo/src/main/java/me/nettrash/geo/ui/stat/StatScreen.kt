package me.nettrash.geo.ui.stat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import me.nettrash.geo.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.nettrash.geo.data.db.Trip
import me.nettrash.geo.data.model.GraphLine
import me.nettrash.geo.ui.GeoViewModel
import me.nettrash.geo.ui.components.GeoGraphPointsView
import me.nettrash.geo.ui.components.GeoGraphView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StatScreen(modifier: Modifier = Modifier, viewModel: GeoViewModel) {
    val pressureData by viewModel.pressureDataSet.collectAsState()
    val pressureMin by viewModel.pressureMin.collectAsState()
    val pressureMax by viewModel.pressureMax.collectAsState()

    val barometerAltData by viewModel.barometerAltDataSet.collectAsState()
    val barometerAltMin by viewModel.barometerAltMin.collectAsState()
    val barometerAltMax by viewModel.barometerAltMax.collectAsState()

    val gpsAltData by viewModel.gpsAltDataSet.collectAsState()
    val gpsAltMin by viewModel.gpsAltMin.collectAsState()
    val gpsAltMax by viewModel.gpsAltMax.collectAsState()

    val trackingData by viewModel.trackingDataSet.collectAsState()
    val trackingMin by viewModel.trackingMin.collectAsState()
    val trackingMax by viewModel.trackingMax.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }

    val thinAirLine = GraphLine(4500f, stringResource(R.string.graph_thin_air), 0xFFFFFF00)
    val deathZoneLine = GraphLine(7980f, stringResource(R.string.graph_death_zone), 0xFFFF0000)
    val seaLevelLine = GraphLine(0f, stringResource(R.string.graph_sea_level), 0xFF0000FF)
    val normalPressureLine = GraphLine(760f, stringResource(R.string.graph_normal), 0xFF4CAF50)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            painter = painterResource(id = R.drawable.geo_big),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alpha = 0.02f,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Text(
            stringResource(R.string.section_tracking),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )

        val trackingLines = buildList {
            add(thinAirLine)
            add(deathZoneLine)
            if (trackingMin < 0) add(seaLevelLine)
        }

        GeoGraphPointsView(
            caption = stringResource(R.string.graph_caption_tracking_altitude),
            data = trackingData,
            lines = trackingLines,
            min = trackingMin,
            max = trackingMax,
            colors = listOf(Color.White, Color(0xFFFF9800)),
            legend = listOf(
                stringResource(R.string.graph_legend_barometer),
                stringResource(R.string.graph_legend_gps)
            ),
            measurement = "m"
        )

        Text(
            stringResource(R.string.section_statistics),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )

        GeoGraphView(
            caption = stringResource(R.string.graph_caption_pressure),
            data = pressureData,
            lines = listOf(normalPressureLine),
            min = pressureMin,
            max = pressureMax,
            measurement = "mm Hg"
        )

        val barometerLines = buildList {
            add(thinAirLine)
            add(deathZoneLine)
            if (barometerAltMin < 0) add(seaLevelLine)
        }

        GeoGraphView(
            caption = stringResource(R.string.graph_caption_altitude_barometer),
            data = barometerAltData,
            lines = barometerLines,
            min = barometerAltMin,
            max = barometerAltMax,
            measurement = "m"
        )

        val gpsLines = buildList {
            add(thinAirLine)
            add(deathZoneLine)
            if (gpsAltMin < 0) add(seaLevelLine)
        }

        GeoGraphView(
            caption = stringResource(R.string.graph_caption_altitude_gps),
            data = gpsAltData,
            lines = gpsLines,
            min = gpsAltMin,
            max = gpsAltMax,
            measurement = "m"
        )

        TripsSection(viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { showClearConfirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                stringResource(R.string.action_clear_history),
                fontSize = 14.sp,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        } // Column

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(stringResource(R.string.clear_history_title)) },
                text = { Text(stringResource(R.string.clear_history_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearConfirm = false
                            viewModel.clearHistory()
                        }
                    ) {
                        Text(stringResource(R.string.action_clear_history))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    } // Box
}

// MARK: - Trip Recorder UI (M5c)

/** One-tap record control + saved-trip list. Mirrors iOS `TripsSectionView`. */
@Composable
private fun TripsSection(viewModel: GeoViewModel) {
    val startedAt by viewModel.tripStartedAt.collectAsState()
    val trips by viewModel.trips.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadTrips() }

    var showNameDialog by remember { mutableStateOf(false) }
    var tripName by remember { mutableStateOf("") }
    var detailTrip by remember { mutableStateOf<Trip?>(null) }

    Text(
        "TRIPS",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.padding(16.dp)
    )

    val start = startedAt
    if (start != null) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(start) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        Text(
            "● Recording — ${formatDuration((now - start) / 1000.0)}",
            color = Color(0xFFFF3B30),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(4.dp)) {
            Button(
                onClick = { tripName = defaultTripName(start); showNameDialog = true },
                shape = RoundedCornerShape(8.dp)
            ) { Text("Stop & Save", color = Color.White) }
            Button(
                onClick = { viewModel.cancelTrip() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                shape = RoundedCornerShape(8.dp)
            ) { Text("Discard", color = Color.White) }
        }
    } else {
        Button(
            onClick = { viewModel.startTrip() },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(4.dp)
        ) { Text("Start trip", color = Color.White) }
    }

    if (trips.isEmpty()) {
        Text(
            "No trips yet — tap Start to record an outing.",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    } else {
        trips.forEach { trip -> TripCard(trip) { detailTrip = trip } }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Name this trip") },
            text = {
                OutlinedTextField(value = tripName, onValueChange = { tripName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    val name = tripName.trim().ifEmpty { defaultTripName(System.currentTimeMillis()) }
                    viewModel.stopTrip(name)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    detailTrip?.let { trip ->
        TripDetailDialog(
            trip = trip,
            viewModel = viewModel,
            onDelete = { viewModel.deleteTrip(trip); detailTrip = null },
            onDismiss = { detailTrip = null }
        )
    }
}

@Composable
private fun TripCard(trip: Trip, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(trip.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(formatStamp(trip.startDate), color = Color.Gray, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("↑ ${trip.totalAscent.roundToInt()} m", color = Color.White, fontSize = 12.sp)
            Text(formatKm(trip.distance), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TripDetailDialog(
    trip: Trip,
    viewModel: GeoViewModel,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val profile by produceState(initialValue = emptyList<Double>(), trip) {
        value = viewModel.tripElevationProfile(trip)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(trip.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(formatStamp(trip.startDate), color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (profile.size > 1) {
                    ElevationProfileCanvas(profile)
                    Spacer(Modifier.height(8.dp))
                }
                tripStatRow("Ascent", "↑ ${trip.totalAscent.roundToInt()} m")
                tripStatRow("Descent", "↓ ${trip.totalDescent.roundToInt()} m")
                tripStatRow("Max altitude", "${trip.maxAltitude.roundToInt()} m")
                tripStatRow("Min altitude", "${trip.minAltitude.roundToInt()} m")
                tripStatRow("Distance", formatKm(trip.distance))
                tripStatRow("Moving time", formatDuration(trip.movingTime))
                tripStatRow("Total time", formatDuration((trip.endDate - trip.startDate) / 1000.0))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF3B30)) } }
    )
}

@Composable
private fun tripStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ElevationProfileCanvas(profile: List<Double>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
    ) {
        if (profile.size > 1) {
            val minV = profile.minOrNull() ?: 0.0
            val maxV = profile.maxOrNull() ?: 1.0
            val range = (maxV - minV).coerceAtLeast(1.0)
            val path = Path()
            profile.forEachIndexed { i, v ->
                val x = size.width * i / (profile.size - 1)
                val y = size.height * (1f - ((v - minV) / range).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Color(0xFFFF9800), style = Stroke(width = 3f))
        }
    }
}

private fun formatKm(m: Double): String = String.format(Locale.US, "%.2f km", m / 1000.0)

private fun formatDuration(seconds: Double): String {
    val t = maxOf(0L, seconds.toLong())
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private fun formatStamp(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))

private fun defaultTripName(ms: Long): String =
    "Trip ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))}"
