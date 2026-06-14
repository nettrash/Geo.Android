package me.nettrash.geo.ui.info

import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import me.nettrash.geo.sensor.DeviceMotionManager
import me.nettrash.geo.sensor.PressureTrendClass
import me.nettrash.geo.ui.GeoViewModel
import me.nettrash.geo.util.GeoCalculations
import me.nettrash.geo.util.Solar
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun InfoScreen(modifier: Modifier = Modifier, viewModel: GeoViewModel) {
    val pressure by viewModel.barometerManager.pressure.collectAsState()
    val height by viewModel.barometerManager.height.collectAsState()
    val everest by viewModel.barometerManager.everest.collectAsState()
    val hasAbsoluteFix by viewModel.barometerManager.hasAbsoluteFix.collectAsState()
    val pressureTrend by viewModel.pressureTrend.collectAsState()

    // Recompute the de-trended trend chip when the Info tab is shown.
    LaunchedEffect(Unit) { viewModel.refreshIfNeeded() }

    // Run the compass only while the Info tab is visible (low-power, AR-free
    // peak direction finder). start()/stop() are idempotent.
    DisposableEffect(Unit) {
        viewModel.motionManager.start()
        onDispose { viewModel.motionManager.stop() }
    }
    val location by viewModel.locationManager.location.collectAsState()
    val closestMountain by viewModel.locationManager.closestMountain.collectAsState()
    val closestDistance by viewModel.locationManager.closestMountainDistance.collectAsState()
    val highestMountain by viewModel.locationManager.highestMountain.collectAsState()
    val highestDistance by viewModel.locationManager.highestMountainDistance.collectAsState()

    val context = LocalContext.current
    val unitLat = stringResource(R.string.unit_latitude)
    val unitLon = stringResource(R.string.unit_longitude)

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
            if (pressureTrend.classification != PressureTrendClass.UNKNOWN) {
                InfoRow(stringResource(R.string.field_trend)) {
                    Text(
                        text = trendLabel(pressureTrend.classification, pressureTrend.changeHpaOver3h),
                        color = trendColor(pressureTrend.classification),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End
                    )
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
                    MonoText("${String.format(Locale.US, "%.6f", location?.latitude ?: 0.0)} $unitLat")
                    MonoText("${String.format(Locale.US, "%.6f", location?.longitude ?: 0.0)} $unitLon")
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

        // SUN section — today's solar windows + live countdown.
        SolarInfoCard(location)

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
            PeakBearingRow(
                userLat = location?.latitude, userLon = location?.longitude,
                userAlt = location?.altitude ?: 0.0,
                peakLat = closestMountain?.coordinates?.latitude,
                peakLon = closestMountain?.coordinates?.longitude,
                motionManager = viewModel.motionManager
            )
            InfoRow(stringResource(R.string.field_coordinates)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.6f", closestMountain?.coordinates?.latitude ?: 0.0)} $unitLat")
                    MonoText("${String.format(Locale.US, "%.6f", closestMountain?.coordinates?.longitude ?: 0.0)} $unitLon")
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
                            // Generic geo: URI (no setPackage) so any
                            // installed maps app can handle it — avoids
                            // ActivityNotFoundException on devices without
                            // Google Maps. Matches MapScreen's detail sheet.
                            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${m.name})")
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
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
            PeakBearingRow(
                userLat = location?.latitude, userLon = location?.longitude,
                userAlt = location?.altitude ?: 0.0,
                peakLat = highestMountain?.coordinates?.latitude,
                peakLon = highestMountain?.coordinates?.longitude,
                motionManager = viewModel.motionManager
            )
            InfoRow(stringResource(R.string.field_coordinates)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText("${String.format(Locale.US, "%.6f", highestMountain?.coordinates?.latitude ?: 0.0)} $unitLat")
                    MonoText("${String.format(Locale.US, "%.6f", highestMountain?.coordinates?.longitude ?: 0.0)} $unitLon")
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
                            // Generic geo: URI (no setPackage) so any
                            // installed maps app can handle it — avoids
                            // ActivityNotFoundException on devices without
                            // Google Maps. Matches MapScreen's detail sheet.
                            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${m.name})")
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
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

/** Short label for the de-trended 3-hour tendency, with the magnitude
 *  for the falling/rising classes. Mirrors iOS `trendLabel`. */
private fun trendLabel(cls: PressureTrendClass, changeHpaOver3h: Double): String {
    val drop = kotlin.math.abs(changeHpaOver3h)
    return when (cls) {
        PressureTrendClass.FALLING_FAST -> String.format(Locale.US, "↓↓ Falling fast (%.0f hPa)", drop)
        PressureTrendClass.FALLING -> String.format(Locale.US, "↓ Falling (%.0f hPa)", drop)
        PressureTrendClass.STEADY -> "→ Steady"
        PressureTrendClass.RISING -> String.format(Locale.US, "↑ Rising (%.0f hPa)", drop)
        PressureTrendClass.UNKNOWN -> ""
    }
}

private fun trendColor(cls: PressureTrendClass): Color = when (cls) {
    PressureTrendClass.FALLING_FAST -> Color(0xFFE53935) // red
    PressureTrendClass.FALLING -> Color(0xFFFFC107)      // amber
    PressureTrendClass.RISING -> Color(0xFF43A047)       // green
    PressureTrendClass.STEADY, PressureTrendClass.UNKNOWN -> Color(0xFFB0BEC5) // muted
}

/**
 * Today's solar windows for the user's exact position and altitude with a
 * live "X h to sunset" countdown. 100 % on-device via the shared [Solar]
 * (NOAA) math; the altitude horizon-dip shifts sunrise earlier and sunset
 * later from a summit. Mirrors iOS `SolarInformationView`.
 */
@Composable
private fun SolarInfoCard(location: android.location.Location?) {
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }

    // Tick once a second so the countdown stays live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    InfoCard(watermark = stringResource(R.string.section_solar)) {
        if (location == null) {
            InfoRow(stringResource(R.string.field_countdown)) {
                MonoText(stringResource(R.string.solar_waiting))
            }
            return@InfoCard
        }

        val lat = location.latitude
        val lon = location.longitude
        val alt = location.altitude
        // Recompute the day's windows only when position or the local
        // calendar day changes — NOT every second. The countdown row below
        // is the only thing driven off the per-second `now` tick. Mirrors
        // iOS, where `times` is computed once and only the countdown ticks.
        val tz = remember { java.util.TimeZone.getDefault() }
        val localDayKey = (now + tz.getOffset(now)) / 86_400_000L
        val times = remember(lat, lon, alt, localDayKey) { Solar.times(now, lat, lon, alt) }

        InfoRow(stringResource(R.string.field_countdown)) {
            MonoText(solarCountdownText(context, times, now, lat, lon, alt))
        }
        InfoRow(stringResource(R.string.field_dawn)) { MonoText(timeOrDash(times.civilDawn, timeFormat)) }
        InfoRow(stringResource(R.string.field_sunrise)) { MonoText(timeOrDash(times.sunrise, timeFormat)) }
        InfoRow(stringResource(R.string.field_golden_am)) {
            MonoText(rangeOrDash(times.goldenDawn, times.goldenMorningEnd, timeFormat))
        }
        InfoRow(stringResource(R.string.field_solar_noon)) { MonoText(timeOrDash(times.solarNoon, timeFormat)) }
        InfoRow(stringResource(R.string.field_golden_pm)) {
            MonoText(rangeOrDash(times.goldenEveningStart, times.goldenDusk, timeFormat))
        }
        InfoRow(stringResource(R.string.field_sunset)) { MonoText(timeOrDash(times.sunset, timeFormat)) }
        InfoRow(stringResource(R.string.field_dusk)) { MonoText(timeOrDash(times.civilDusk, timeFormat)) }
        InfoRow(stringResource(R.string.field_day_length)) { MonoText(durationOrDash(times.dayLengthMs)) }
    }
}

/** Next horizon crossing to count down to: today's sunrise, then today's
 *  sunset, then tomorrow's sunrise. Mirrors iOS `nextEvent`. */
private fun solarCountdownText(
    context: android.content.Context,
    times: Solar.Times, now: Long, lat: Double, lon: Double, alt: Double
): String {
    times.sunrise?.let { if (now < it) return "${countdownString(it - now)} to sunrise" }
    times.sunset?.let { if (now < it) return "${countdownString(it - now)} to sunset" }
    if (times.isPolarDay) return context.getString(R.string.solar_sun_up)
    if (times.isPolarNight) return context.getString(R.string.solar_polar_night)
    // Advance one *local* day (DST-aware) rather than a fixed 86 400 000 ms,
    // which would skip a day in the late evening before a spring-forward.
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    val tomorrow = Solar.times(cal.timeInMillis, lat, lon, alt)
    tomorrow.sunrise?.let { return "${countdownString(it - now)} to sunrise" }
    return "—"
}

private fun countdownString(ms: Long): String {
    val s = maxOf(0L, ms) / 1000L
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${sec}s"
}

private fun timeOrDash(ms: Long?, fmt: DateFormat): String =
    ms?.let { fmt.format(Date(it)) } ?: "—"

private fun rangeOrDash(a: Long?, b: Long?, fmt: DateFormat): String =
    if (a != null && b != null) "${fmt.format(Date(a))}–${fmt.format(Date(b))}" else "—"

private fun durationOrDash(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    val total = ms / 1000L
    return "${total / 3600}h ${(total % 3600) / 60}m"
}

/**
 * Distance-paired "point me toward it" row: a true-bearing readout
 * ("117° SE") plus an arrow that rotates to the device's live heading so it
 * always points at the peak. A low-power, AR-free direction finder.
 * Mirrors iOS `PeakBearingRow`.
 *
 * The rotation-vector sensor reports a MAGNETIC azimuth, so it's converted
 * to a true heading via the local magnetic declination before being paired
 * with the (true) bearing — iOS already gets `CLHeading.trueHeading`.
 * Renders nothing until both observer and peak coordinates are known.
 */
@Composable
private fun PeakBearingRow(
    userLat: Double?, userLon: Double?, userAlt: Double,
    peakLat: Double?, peakLon: Double?,
    motionManager: DeviceMotionManager
) {
    val valid = userLat != null && userLon != null && peakLat != null && peakLon != null &&
        !(userLat == 0.0 && userLon == 0.0) && !(peakLat == 0.0 && peakLon == 0.0)
    if (!valid) return

    val heading by motionManager.heading.collectAsState()
    val accuracy by motionManager.headingAccuracy.collectAsState()

    val bearing = GeoCalculations.bearing(userLat!!, userLon!!, peakLat!!, peakLon!!)
    val declination = remember(userLat, userLon) {
        GeomagneticField(userLat.toFloat(), userLon.toFloat(), userAlt.toFloat(), System.currentTimeMillis()).declination
    }
    val trueHeading = (((heading + declination) % 360) + 360) % 360
    val rotation = (bearing - trueHeading).toFloat()

    InfoRow(stringResource(R.string.field_bearing)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Navigation glyph points up; rotating by (bearing − heading) aims
            // it at the peak's real-world direction relative to the device.
            Icon(
                imageVector = Icons.Filled.Navigation,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp).rotate(rotation)
            )
            Spacer(Modifier.width(6.dp))
            MonoText("${bearing.roundToInt()}° ${GeoCalculations.cardinalDirection(bearing)}")
        }
    }
    if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = stringResource(R.string.compass_calibrate),
                color = Color(0xFFFFC107),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
