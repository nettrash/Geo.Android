package me.nettrash.geo.ui.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import me.nettrash.geo.R
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import me.nettrash.geo.data.model.MountainInfo
import me.nettrash.geo.ui.GeoViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(modifier: Modifier = Modifier, viewModel: GeoViewModel) {
    val location by viewModel.locationManager.location.collectAsState()
    val mountainsData by viewModel.mountainsData.collectAsState()
    val historyItems by viewModel.historyItems.collectAsState()
    val context = LocalContext.current

    var selectedMountain by remember { mutableStateOf<MountainInfo?>(null) }
    var selectedHistoryDate by remember { mutableStateOf<Long?>(null) }
    var showMountainSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val userLatLng = location?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userLatLng, 8f)
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = location != null,
                mapType = MapType.TERRAIN
            ),
            uiSettings = MapUiSettings(
                compassEnabled = true,
                myLocationButtonEnabled = true,
                zoomControlsEnabled = true
            )
        ) {
            // Seven Peaks markers (orange)
            mountainsData?.sevenPeaks?.mountains?.forEach { mountain ->
                val lat = mountain.coordinates?.latitude ?: return@forEach
                val lon = mountain.coordinates.longitude ?: return@forEach
                Marker(
                    state = MarkerState(position = LatLng(lat, lon)),
                    title = mountain.name ?: "Unknown",
                    snippet = "${mountain.height ?: 0} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_top_seven),
                    onClick = {
                        selectedMountain = mountain
                        showMountainSheet = true
                        true
                    }
                )
            }

            // Highest mountains
            val sevenPeakNames = mountainsData?.sevenPeaks?.mountains?.map { it.name }?.toSet() ?: emptySet()
            mountainsData?.highest?.mountains?.filter { it.name !in sevenPeakNames }?.forEach { mountain ->
                val lat = mountain.coordinates?.latitude ?: return@forEach
                val lon = mountain.coordinates.longitude ?: return@forEach
                Marker(
                    state = MarkerState(position = LatLng(lat, lon)),
                    title = mountain.name ?: "Unknown",
                    snippet = "${mountain.height ?: 0} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_top_peaks),
                    onClick = {
                        selectedMountain = mountain
                        showMountainSheet = true
                        true
                    }
                )
            }

            // Snow Leopard peaks
            val allOtherNames = sevenPeakNames + (mountainsData?.highest?.mountains?.map { it.name }?.toSet() ?: emptySet())
            mountainsData?.snowLeopardOfRussia?.mountains?.filter { it.name !in allOtherNames }?.forEach { mountain ->
                val lat = mountain.coordinates?.latitude ?: return@forEach
                val lon = mountain.coordinates.longitude ?: return@forEach
                Marker(
                    state = MarkerState(position = LatLng(lat, lon)),
                    title = mountain.name ?: "Unknown",
                    snippet = "${mountain.height ?: 0} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_snow_leopard),
                    onClick = {
                        selectedMountain = mountain
                        showMountainSheet = true
                        true
                    }
                )
            }

            // History points
            historyItems.takeLast(25).forEach { item ->
                Marker(
                    state = MarkerState(position = LatLng(item.gpsLatitude, item.gpsLongitude)),
                    title = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.recordDate)),
                    snippet = "Alt: ${String.format(Locale.US, "%.0f", item.gpsAltitude)} m",
                    icon = BitmapDescriptorFactory.fromResource(R.drawable.pin_history_point),
                    onClick = {
                        selectedHistoryDate = item.recordDate
                        showHistorySheet = true
                        true
                    }
                )
            }
        }

        // Mountain detail sheet
        if (showMountainSheet && selectedMountain != null) {
            ModalBottomSheet(
                onDismissRequest = { showMountainSheet = false },
                sheetState = sheetState,
                containerColor = Color(0xFF1A1A1A)
            ) {
                val mountain = selectedMountain!!
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        mountain.name ?: "Unknown",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Height", "${mountain.height ?: 0} m")
                    DetailRow("Region", mountain.partOfTheWorld ?: "-")
                    DetailRow("Country", mountain.country ?: "-")
                    DetailRow("Location", mountain.location ?: "-")
                    DetailRow("Coordinates",
                        "${String.format(Locale.US, "%.6f", mountain.coordinates?.latitude ?: 0.0)}, " +
                        "${String.format(Locale.US, "%.6f", mountain.coordinates?.longitude ?: 0.0)}")
                    DetailRow("First Ascent", mountain.firstAscent ?: "-")

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val lat = mountain.coordinates?.latitude ?: return@Button
                            val lon = mountain.coordinates.longitude ?: return@Button
                            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${mountain.name})")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Show on Map", color = Color.White)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // History detail sheet
        if (showHistorySheet && selectedHistoryDate != null) {
            val item = historyItems.find { it.recordDate == selectedHistoryDate }
            if (item != null) {
                ModalBottomSheet(
                    onDismissRequest = { showHistorySheet = false },
                    sheetState = sheetState,
                    containerColor = Color(0xFF1A1A1A)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()).format(Date(item.recordDate))
                        Text(dateStr, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        DetailRow("Pressure", "${String.format(Locale.US, "%.4f", item.barometerPressure)} kPa")
                        DetailRow("Pressure", "${String.format(Locale.US, "%.4f", item.barometerPressure * 7.50062)} mm Hg")
                        DetailRow("Pressure", "${String.format(Locale.US, "%.4f", item.barometerPressure / 101.325)} atm")
                        DetailRow("Bar. Altitude", "${String.format(Locale.US, "%.0f", item.barometerAltitude)} m")
                        DetailRow("GPS Latitude", "${String.format(Locale.US, "%.6f", item.gpsLatitude)}")
                        DetailRow("GPS Longitude", "${String.format(Locale.US, "%.6f", item.gpsLongitude)}")
                        DetailRow("GPS Altitude", "${String.format(Locale.US, "%.0f", item.gpsAltitude)} m")
                        DetailRow("Velocity", "${String.format(Locale.US, "%.1f", item.gpsVelocity)} m/s")
                        DetailRow("Velocity", "${String.format(Locale.US, "%.1f", item.gpsVelocity * 3.6)} km/h")
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Text(value, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}
