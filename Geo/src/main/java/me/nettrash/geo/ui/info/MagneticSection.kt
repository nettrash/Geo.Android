package me.nettrash.geo.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.R
import me.nettrash.geo.ui.GeoViewModel
import me.nettrash.geo.util.AuroraAlertStore
import me.nettrash.geo.util.AuroraVisibility
import me.nettrash.geo.util.CompassBand
import me.nettrash.geo.util.Freshness
import me.nettrash.geo.util.GScale
import me.nettrash.geo.util.Geomagnetic
import me.nettrash.geo.util.GnssAdvisory
import me.nettrash.geo.util.KpProvenance
import me.nettrash.geo.util.MagneticConditions
import me.nettrash.geo.worker.SpaceWeatherWorker
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Orange used for the explainer's section headings — the same accent the
 *  offline-pack dialog uses, so the two sheets read as one app. */
private val MAGNETIC_ACCENT = Color(0xFFFF9800)

/**
 * Info-tab card for magnetic conditions: the planetary K index from NOAA
 * SWPC, turned into the three things a geomagnetic storm actually changes
 * for a navigator — the compass, GPS, and whether the aurora can reach
 * this latitude tonight. Mirrors iOS `MagneticInformationView`.
 *
 * Its own file rather than another block inside `InfoScreen.kt`, which is
 * already ~700 lines — the `OfflineExpeditionCard` precedent.
 *
 * Five rows, against the app's verified eight-row ceiling (the Sun card).
 * Field / Compass / GPS come off the fetched Kp and are hidden outright
 * when there is none — a dash there would read as "no storm". Aurora and
 * Magnetic lat are properties of the PLACE, need no network, and stay.
 *
 * Nothing on this card is measured by the phone. See the honesty contract
 * atop [Geomagnetic] for what the copy may and may not claim.
 */
@Composable
fun MagneticInfoCard(viewModel: GeoViewModel) {
    val conditions by viewModel.magneticConditions.collectAsState()
    val checking by viewModel.spaceWeatherChecking.collectAsState()
    var showExplainer by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    // Day + month only: "until 12 Aug" is a date to plan around, not an
    // instant. Device locale, unlike the numeric readouts.
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }

    InfoCard(watermark = stringResource(R.string.section_magnetic)) {
        val kp = conditions.kpNow

        if (kp != null) {
            InfoRow(stringResource(R.string.field_geomag_field)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(
                            R.string.geomag_field_value,
                            String.format(Locale.US, "%.1f", kp),
                            stringResource(gScaleLabel(conditions.gScale))
                        ),
                        color = gScaleColor(conditions.gScale),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End
                    )
                    fieldCaption(conditions)?.let { CaptionText(it) }
                }
            }

            InfoRow(stringResource(R.string.field_geomag_compass)) {
                Column(horizontalAlignment = Alignment.End) {
                    MonoText(stringResource(compassLabel(conditions.compass)))
                    // Below G3 the swing is smaller than the crustal
                    // anomalies a hiker already walks through, so there is
                    // nothing worth qualifying.
                    if (conditions.gScale >= GScale.G3) {
                        CaptionText(
                            stringResource(
                                if (conditions.gScale == GScale.G5) {
                                    R.string.geomag_compass_caption_g5
                                } else {
                                    R.string.geomag_compass_caption
                                }
                            )
                        )
                    }
                }
            }

            InfoRow(stringResource(R.string.field_geomag_gps)) {
                MonoText(stringResource(gnssLabel(conditions.gnss)))
            }
        }

        InfoRow(stringResource(R.string.field_geomag_aurora)) {
            Column(horizontalAlignment = Alignment.End) {
                MonoText(auroraValue(conditions, dateFormat))
                auroraWindowCaption(conditions, timeFormat)?.let { CaptionText(it) }
                auroraRangeCaption(conditions)?.let { CaptionText(it) }
            }
        }

        // The row that explains why two users at the same Kp see different
        // verdicts. Suppressed only when the correction grid failed to
        // decode — never replaced by the raw dipole, which is 5.63° too far
        // poleward at London.
        conditions.magneticLatitudeDeg?.let { mlat ->
            InfoRow(stringResource(R.string.field_geomag_mlat)) {
                MonoText(
                    stringResource(
                        if (mlat >= 0.0) R.string.geomag_mlat_north else R.string.geomag_mlat_south,
                        String.format(Locale.US, "%.1f", abs(mlat))
                    )
                )
            }
        }

        // A fetch in flight with nothing cached gets one line, never a
        // spinner and never a blocked card.
        if (checking && conditions.freshness != Freshness.LIVE && conditions.freshness != Freshness.CACHED) {
            InfoRow(stringResource(R.string.geomag_checking)) {
                MonoText("…")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = footerText(conditions),
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showExplainer = true }) {
                Text(
                    stringResource(R.string.geomag_explain_action),
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
    }

    if (showExplainer) {
        MagneticExplainerDialog(onDismiss = { showExplainer = false })
    }
}

/** Secondary line under a value: the bin's provenance, tonight's window,
 *  the compass qualifier. Monospace like the value it hangs off. */
@Composable
private fun CaptionText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End
    )
}

// ── Row content ─────────────────────────────────────────────────────

/** Severity ramp for the G scale, mirroring `trendColor` on the barometer
 *  card so one glance reads the same way on both. */
private fun gScaleColor(g: GScale): Color = when (g) {
    GScale.G0 -> Color(0xFFB0BEC5)              // muted — quiet field
    GScale.G1 -> Color(0xFF43A047)              // green
    GScale.G2 -> Color(0xFFFFC107)              // amber
    GScale.G3 -> Color(0xFFFF9800)              // orange
    GScale.G4, GScale.G5 -> Color(0xFFE53935)   // red
}

private fun gScaleLabel(g: GScale): Int = when (g) {
    GScale.G0 -> R.string.geomag_g0
    GScale.G1 -> R.string.geomag_g1
    GScale.G2 -> R.string.geomag_g2
    GScale.G3 -> R.string.geomag_g3
    GScale.G4 -> R.string.geomag_g4
    GScale.G5 -> R.string.geomag_g5
}

private fun compassLabel(band: CompassBand): Int = when (band) {
    CompassBand.NORMAL -> R.string.geomag_compass_normal
    CompassBand.UNDER_ONE -> R.string.geomag_compass_under_one
    CompassBand.ONE_TO_TWO -> R.string.geomag_compass_one_to_two
    CompassBand.TWO_TO_FIVE -> R.string.geomag_compass_two_to_five
    CompassBand.OVER_FIVE -> R.string.geomag_compass_over_five
}

private fun gnssLabel(advisory: GnssAdvisory): Int = when (advisory) {
    GnssAdvisory.NOMINAL -> R.string.geomag_gnss_nominal
    GnssAdvisory.MAY_DEGRADE -> R.string.geomag_gnss_may_degrade
    GnssAdvisory.DEGRADED -> R.string.geomag_gnss_degraded
}

/** "observed, 21–00 UT" — NOAA's own tag for the bin, plus the bin's own
 *  UT span, so a forecast figure is never mistaken for an observation. */
@Composable
private fun fieldCaption(conditions: MagneticConditions): String? {
    val binStart = conditions.binStartMs ?: return null
    val provenance = when (conditions.kpProvenance) {
        KpProvenance.OBSERVED -> R.string.geomag_provenance_observed
        KpProvenance.ESTIMATED -> R.string.geomag_provenance_estimated
        KpProvenance.PREDICTED -> R.string.geomag_provenance_predicted
        null -> return null
    }
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = binStart
    val startHour = utc.get(Calendar.HOUR_OF_DAY)
    val endHour = (startHour + Geomagnetic.KP_BIN_HOURS.toInt()) % 24
    return stringResource(
        R.string.geomag_field_caption,
        stringResource(provenance),
        stringResource(R.string.geomag_bin_span, startHour, endHour)
    )
}

/**
 * The aurora verdict. Branch order matters and is the same on both
 * platforms: no fix, then no grid, then no darkness at all, then the
 * offline "what would it take" answer, then the oval itself.
 *
 * Every phrasing is either geometric ("Oval reaches you") or hedged
 * ("Possible glow") — the model has no brightness term, so no brightness
 * adjective may appear here.
 */
@Composable
private fun auroraValue(conditions: MagneticConditions, dateFormat: DateFormat): String {
    val aurora = conditions.aurora
    if (aurora.poleBearingDeg == null) return stringResource(R.string.solar_waiting)
    val mlat = conditions.magneticLatitudeDeg
        ?: return stringResource(R.string.geomag_aurora_unavailable)

    // Polar summer: reachability is beside the point when the sky never
    // gets dark, so say the thing that actually decides tonight.
    if (!aurora.hasDarkness) {
        aurora.nextDarknessMs?.let {
            return stringResource(R.string.geomag_aurora_no_darkness, dateFormat.format(Date(it)))
        }
    }

    // "about Kp 5+" comes out of the core, not out of a format string:
    // the phrase carries the honesty rule that a Kp threshold is never a
    // decimal, and it must read identically on both platforms.
    val threshold = aurora.kpNeededForHorizonGlow
    val thresholdLabel = Geomagnetic.requiredKpLabel(mlat)
    if (conditions.kpNow == null || aurora.visibility == AuroraVisibility.UNKNOWN) {
        // No usable Kp: answer the question the place can answer on its
        // own, which is what makes this card work with no signal.
        return when {
            thresholdLabel == null -> stringResource(R.string.geomag_aurora_not_visible_ever)
            threshold != null && threshold <= 0.0 -> stringResource(R.string.geomag_aurora_any_night)
            else -> stringResource(R.string.geomag_aurora_needs_kp, thresholdLabel)
        }
    }

    val northern = aurora.isNorthernHemisphere
    return when (aurora.visibility) {
        AuroraVisibility.OVERHEAD -> stringResource(R.string.geomag_aurora_overhead)
        AuroraVisibility.OVAL_REACHES -> stringResource(
            if (northern) R.string.geomag_aurora_reaches_north else R.string.geomag_aurora_reaches_south
        )
        AuroraVisibility.HORIZON_GLOW -> stringResource(
            if (northern) R.string.geomag_aurora_horizon_north else R.string.geomag_aurora_horizon_south
        )
        AuroraVisibility.NOT_VISIBLE, AuroraVisibility.UNKNOWN -> when (thresholdLabel) {
            null -> stringResource(R.string.geomag_aurora_not_visible_ever)
            else -> stringResource(R.string.geomag_aurora_not_visible, thresholdLabel)
        }
    }
}

/** "22:41–03:12 · look N 15° W, low" — tonight's dark window, plus which
 *  way to face when there is something to face. */
@Composable
private fun auroraWindowCaption(conditions: MagneticConditions, timeFormat: DateFormat): String? {
    val aurora = conditions.aurora
    val start = aurora.windowStartMs ?: return null
    val end = aurora.windowEndMs ?: return null
    var caption = stringResource(
        R.string.geomag_aurora_window,
        timeFormat.format(Date(start)),
        timeFormat.format(Date(end))
    )
    // The bearing is a property of the PLACE, so it survives having no Kp
    // at all (visibility UNKNOWN) — that is the offline tier. It is dropped
    // only when there is nothing to face: overhead already says "look up",
    // and out of reach has no direction worth giving.
    val bearing = aurora.poleBearingDeg
    val worthFacing = aurora.visibility != AuroraVisibility.OVERHEAD &&
        aurora.visibility != AuroraVisibility.NOT_VISIBLE
    if (worthFacing && bearing != null) {
        caption = stringResource(R.string.geomag_aurora_window_look, caption, quadrantBearing(bearing))
    }
    if (aurora.visibility == AuroraVisibility.HORIZON_GLOW) {
        caption = stringResource(R.string.geomag_aurora_low_suffix, caption)
    }
    return caption
}

/** "~500 km north of you — needs a clear, open horizon". Only for the
 *  horizon case: it is the one verdict that depends on the observer having
 *  an unobstructed view of somewhere they cannot stand. */
@Composable
private fun auroraRangeCaption(conditions: MagneticConditions): String? {
    val aurora = conditions.aurora
    if (aurora.visibility != AuroraVisibility.HORIZON_GLOW) return null
    val km = aurora.rangeKm ?: return null
    // Rounded to 10 km: the oval rule ignores magnetic local time, which is
    // worth a couple of degrees of latitude on its own.
    val rounded = (km / 10.0).roundToInt() * 10
    return stringResource(
        if (aurora.isNorthernHemisphere) {
            R.string.geomag_aurora_range_north
        } else {
            R.string.geomag_aurora_range_south
        },
        rounded
    )
}

/**
 * Quadrant notation — "N 15° W" — rather than a 3-figure heading. The
 * geomagnetic pole is never far off north or south, and "345°" reads as a
 * bearing to steer rather than a direction to look.
 */
@Composable
private fun quadrantBearing(bearingDeg: Double): String {
    val bearing = ((bearingDeg % 360.0) + 360.0) % 360.0
    return when {
        bearing <= 90.0 -> {
            val off = bearing.roundToInt()
            if (off == 0) stringResource(R.string.geomag_bearing_north)
            else stringResource(R.string.geomag_bearing_ne, off)
        }
        bearing < 180.0 -> stringResource(R.string.geomag_bearing_se, (180.0 - bearing).roundToInt())
        bearing <= 270.0 -> {
            val off = (bearing - 180.0).roundToInt()
            if (off == 0) stringResource(R.string.geomag_bearing_south)
            else stringResource(R.string.geomag_bearing_sw, off)
        }
        else -> stringResource(R.string.geomag_bearing_nw, (360.0 - bearing).roundToInt())
    }
}

/** Always names the source and the payload's own timestamp — never our
 *  fetch time dressed up as an observation, and never "no network
 *  required", which this card cannot claim. */
@Composable
private fun footerText(conditions: MagneticConditions): String = when (conditions.freshness) {
    // Branch on the tier and nothing else, exactly as iOS `footerText` does.
    // The earlier shape let LIVE fall through into the forecast branch, so a
    // fresh copy whose bins are all still in the future — .live with no
    // covering bin, kpNow null — printed "fetched 1 h ago" under a card
    // carrying no Kp figure at all. No footer may imply data the card is not
    // showing.
    Freshness.LIVE -> {
        val binStart = conditions.binStartMs
        if (binStart == null) {
            stringResource(R.string.geomag_footer_none)
        } else {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utc.timeInMillis = binStart
            stringResource(
                R.string.geomag_footer_live,
                String.format(
                    Locale.US, "%02d:%02d",
                    utc.get(Calendar.HOUR_OF_DAY), utc.get(Calendar.MINUTE)
                )
            )
        }
    }
    // A cached tier only exists because a bin still CONTAINS now, and a bin
    // covering now in a copy fetched hours ago was a forecast row when it was
    // fetched. So the line says forecast: "cached 61 h ago" reads like a stale
    // observation of something that has not happened yet.
    Freshness.CACHED -> {
        val ageMs = conditions.dataAgeMs
        if (ageMs == null) stringResource(R.string.geomag_footer_none) else agePhrase(ageMs)
    }
    Freshness.EXPIRED, Freshness.NONE -> stringResource(R.string.geomag_footer_none)
}

/** "9 h" inside the first day, "3 d" after it. ROUNDED, not truncated — with
 *  integer division a 61 h payload read "2 d" here while iOS rounded the same
 *  copy to "3 d", so one cache printed two different ages depending on which
 *  phone you looked at. Mirrors iOS `agePhrase`. */
@Composable
private fun agePhrase(ageMs: Long): String {
    val hours = ageMs / 3_600_000.0
    return if (hours < 24) {
        stringResource(R.string.geomag_footer_forecast_hours, hours.roundToInt())
    } else {
        stringResource(R.string.geomag_footer_forecast_days, (hours / 24.0).roundToInt())
    }
}

// ── "What this means" ───────────────────────────────────────────────

/**
 * The explainer sheet. Long on purpose: it is the only place the feature
 * is allowed to state its own limits at length, and the one sourced
 * sentence about headaches lives here and is never promoted to a readout.
 * Mirrors the iOS `.sheet`.
 *
 * It also carries the opt-in aurora alert switch, at the bottom, after the
 * limits and the attribution. Somebody who has just read what Geo cannot
 * tell them is in the right frame of mind to decide whether they want it
 * waking them up.
 */
@Composable
private fun MagneticExplainerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val alertStore = remember { AuroraAlertStore(context) }
    var alertsEnabled by remember { mutableStateOf(alertStore.isEnabled()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF222222),
        title = {
            Text(
                stringResource(R.string.geomag_explain_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Body(R.string.geomag_explain_intro)

                Heading(R.string.geomag_explain_compass_heading)
                Body(R.string.geomag_explain_compass_1)
                Body(R.string.geomag_explain_compass_2)
                Body(R.string.geomag_explain_compass_3)

                Heading(R.string.geomag_explain_gps_heading)
                Body(R.string.geomag_explain_gps)

                Heading(R.string.geomag_explain_aurora_heading)
                Body(R.string.geomag_explain_aurora_1)
                Body(R.string.geomag_explain_aurora_2)

                Heading(R.string.geomag_explain_limits_heading)
                Body(R.string.geomag_explain_limits_1)
                Body(R.string.geomag_explain_limits_2)

                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.geomag_explain_data),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(2.dp))
                AuroraAlertsToggle(
                    enabled = alertsEnabled,
                    onChange = { enabled ->
                        alertsEnabled = enabled
                        alertStore.setEnabled(enabled)
                        // Drop the cooldown on the way out, so somebody who
                        // switches alerts back on next winter is not
                        // silenced by a timestamp from this one.
                        if (!enabled) alertStore.clear()
                        // Scheduling follows the flag immediately: turning
                        // this off must stop the background fetch now, not
                        // at the next process start.
                        SpaceWeatherWorker.applyOptIn(context, enabled)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = MAGNETIC_ACCENT)
            }
        }
    )
}

/**
 * The one control in this feature. Default OFF, and it is the only thing
 * in Geo that starts network activity nobody is watching — so the note
 * under it says so plainly rather than leaving it to the privacy policy.
 *
 * Flipping it schedules or cancels [SpaceWeatherWorker]; there is no other
 * way the background refresh can come into existence.
 */
@Composable
private fun AuroraAlertsToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.geomag_alerts_title),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MAGNETIC_ACCENT
            )
        )
    }
    Body(R.string.geomag_alerts_note)
}

@Composable
private fun Heading(resId: Int) {
    Text(
        stringResource(resId),
        color = MAGNETIC_ACCENT,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun Body(resId: Int) {
    Text(
        stringResource(resId),
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp
    )
}
