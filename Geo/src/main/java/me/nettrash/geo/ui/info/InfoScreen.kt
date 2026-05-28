package me.nettrash.geo.ui.info

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import me.nettrash.geo.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.ui.GeoViewModel
import java.util.Locale

@Composable
fun InfoScreen(modifier: Modifier = Modifier, viewModel: GeoViewModel) {
    val pressure by viewModel.barometerManager.pressure.collectAsState()
    val height by viewModel.barometerManager.height.collectAsState()
    val everest by viewModel.barometerManager.everest.collectAsState()
    val hasAbsoluteFix by viewModel.barometerManager.hasAbsoluteFix.collectAsState()
    val location by viewModel.locationManager.location.collectAsState()
    val closestMountain by viewModel.locationManager.closestMountain.collectAsState()
    val closestDistance by viewModel.locationManager.closestMountainDistance.collectAsState()
    val highestMountain by viewModel.locationManager.highestMountain.collectAsState()
    val highestDistance by viewModel.locationManager.highestMountainDistance.collectAsState()

    val context = LocalContext.current

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
                .verticalScroll(rememberScrollState())
        ) {
        // BAROMETER section
        InfoCard(watermark = stringResource(R.string.section_barometer)) {
            InfoRow(stringResource(R.string.field_pressure)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.4f", pressure)} kPa")
                    MonoText("${String.format(Locale.US, "%.4f", pressure * 7.50062)} mm Hg")
                    MonoText("${String.format(Locale.US, "%.4f", pressure / 101.325)} atm")
                }
            }
            InfoRow(stringResource(R.string.field_altitude)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.0f", height)} m")
                    // "calibrating…" hint while QnhRepository hasn't
                    // fetched a real sea-level pressure for our
                    // location yet. The altitude shown is still the
                    // standard-atmosphere fallback, which can be
                    // off by 100–500 m in real weather, so flag it.
                    if (!hasAbsoluteFix) {
                        Text(
                            text = stringResource(R.string.field_calibrating),
                            color = Color(0xFFFFC107),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            InfoRow(stringResource(R.string.field_percent_everest)) {
                MonoText("${String.format(Locale.US, "%.4f", everest * 100.0)} %")
            }
        }

        // SATELLITE section
        InfoCard(watermark = stringResource(R.string.section_satellite)) {
            InfoRow(stringResource(R.string.field_coordinates)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.6f", location?.latitude ?: 0.0)} lt")
                    MonoText("${String.format(Locale.US, "%.6f", location?.longitude ?: 0.0)} lg")
                }
            }
            InfoRow(stringResource(R.string.field_altitude)) {
                MonoText("${String.format(Locale.US, "%.0f", location?.altitude ?: 0.0)} m")
            }
            InfoRow(stringResource(R.string.field_velocity)) {
                val speed = maxOf(location?.speed?.toDouble() ?: 0.0, 0.0)
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.1f", speed)} m/s")
                    MonoText("${String.format(Locale.US, "%.1f", speed * 3.6)} km/h")
                }
            }
        }

        // CLOSEST MOUNTAIN section
        val unknown = stringResource(R.string.fallback_unknown)
        InfoCard(watermark = stringResource(R.string.section_closest_mountain)) {
            InfoRow(stringResource(R.string.field_name)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText(closestMountain?.name ?: unknown)
                    MonoText("${closestMountain?.height ?: 0} m")
                }
            }
            InfoRow(stringResource(R.string.field_distance)) {
                MonoText("${String.format(Locale.US, "%.2f", (closestDistance ?: 0.0) / 1000.0)} km")
            }
            InfoRow(stringResource(R.string.field_coordinates)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.6f", closestMountain?.coordinates?.latitude ?: 0.0)} lt")
                    MonoText("${String.format(Locale.US, "%.6f", closestMountain?.coordinates?.longitude ?: 0.0)} lg")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        closestMountain?.let { m ->
                            val lat = m.coordinates?.latitude ?: return@Button
                            val lon = m.coordinates.longitude ?: return@Button
                            val uri = Uri.parse("google.navigation:q=$lat,$lon")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.google.android.apps.maps")
                            }
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.action_directions),
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }

        // HIGHEST MOUNTAIN section
        InfoCard(watermark = stringResource(R.string.section_highest_mountain)) {
            InfoRow(stringResource(R.string.field_name)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText(highestMountain?.name ?: unknown)
                    MonoText("${highestMountain?.height ?: 0} m")
                }
            }
            InfoRow(stringResource(R.string.field_distance)) {
                MonoText("${String.format(Locale.US, "%.2f", (highestDistance ?: 0.0) / 1000.0)} km")
            }
            InfoRow(stringResource(R.string.field_coordinates)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.6f", highestMountain?.coordinates?.latitude ?: 0.0)} lt")
                    MonoText("${String.format(Locale.US, "%.6f", highestMountain?.coordinates?.longitude ?: 0.0)} lg")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        highestMountain?.let { m ->
                            val lat = m.coordinates?.latitude ?: return@Button
                            val lon = m.coordinates.longitude ?: return@Button
                            val uri = Uri.parse("google.navigation:q=$lat,$lon")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.google.android.apps.maps")
                            }
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.action_directions),
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        } // Column
    } // Box
}

@Composable
fun InfoCard(watermark: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
            .padding(8.dp)
    ) {
        Text(
            text = watermark,
            fontSize = 20.sp,
            color = Color.White.copy(alpha = 0.2f),
            modifier = Modifier
                .align(Alignment.Center)
                .rotate(-25f),
            fontWeight = FontWeight.Bold
        )
        Column {
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        content()
    }
}

@Composable
fun MonoText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End
    )
}
