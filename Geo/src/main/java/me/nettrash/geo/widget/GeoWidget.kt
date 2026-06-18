package me.nettrash.geo.widget

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import me.nettrash.geo.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeoWidget : GlanceAppWidget() {

    // Persist per-instance config (show GPS / show barometer toggles)
    // via the same preferences-backed state Glance uses for the rest
    // of the widget's state. See WidgetConfig.
    override val stateDefinition: GlanceStateDefinition<*>
        get() = WidgetConfig.stateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataStore.read(context)
        provideContent {
            GlanceTheme {
                val prefs = currentState<Preferences>()
                val showBarometer = WidgetConfig.showBarometer(prefs)
                val showGps = WidgetConfig.showGps(prefs)
                WidgetContent(data, showBarometer = showBarometer, showGps = showGps)
            }
        }
    }

    @Composable
    private fun WidgetContent(
        data: WidgetDataStore.Snapshot,
        showBarometer: Boolean,
        showGps: Boolean
    ) {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            // ── Mountain background at 30% opacity (baked into PNG) ──────
            Image(
                provider = ImageProvider(R.drawable.widget_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize()
            )

            // ── Foreground content ───────────────────────────────────────
            // Glance / RemoteViews hosts cap each Column at 10
            // children, so we group section content into nested
            // Columns and use padding instead of Spacer rows. The
            // outer Column ends up with at most 5 children no matter
            // which sections are visible:
            //   1. Header row
            //   2. Top divider
            //   3. Barometer section (Column)
            //   4. Mid divider (only when both sections shown)
            //   5. GPS section (Column)
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {

                // Beyond this age the snapshot is treated as stale: the
                // header shows a relative age ("2 h ago") instead of a
                // current-looking time, and the carried-forward GPS
                // section is dimmed. Kept in sync with the iOS widget
                // (30 min).
                val isStale = data.hasData &&
                    (System.currentTimeMillis() - data.updatedAt) > STALENESS_THRESHOLD_MS

                // ── Header ───────────────────────────────────────────────
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        context.getString(R.string.widget_title),
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(Color(0xFFFFFFFF)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    val timeStr = when {
                        !data.hasData || data.updatedAt <= 0L -> "--:--"
                        isStale -> relativeAge(data.updatedAt)
                        else -> SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(data.updatedAt))
                    }
                    Text(
                        timeStr,
                        style = TextStyle(
                            // Warn-orange when stale so the age stands out.
                            color = androidx.glance.unit.ColorProvider(
                                if (isStale) Color(0xFFFF9800) else Color(0xFF888888)
                            ),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }

                Divider()

                if (!data.hasData) {
                    // First run / cleared storage / decode failure: show a
                    // placeholder instead of authoritative-looking zeros.
                    // Mirrors the iOS widget's "No information" branch.
                    Text(
                        context.getString(R.string.widget_no_data),
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(Color(0xFF888888)),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = GlanceModifier.padding(vertical = 12.dp)
                    )
                } else {
                    if (showBarometer) {
                        BarometerSection(context, data)
                    }

                    if (showBarometer && showGps) {
                        Divider()
                    }

                    if (showGps) {
                        GpsSection(context, data, dimmed = isStale)
                    }
                }
            }
        }
    }

    /**
     * Barometer section as its own nested Column. Has at most 4
     * children (label + 3 data rows) — well under the 10-child cap.
     * Spacing is baked into the section's own top/bottom padding so
     * we don't need Spacer rows.
     */
    @Composable
    private fun BarometerSection(
        context: android.content.Context,
        data: WidgetDataStore.Snapshot
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)) {
            SectionLabel(context.getString(R.string.widget_section_barometer))
            DataRow(
                label = context.getString(R.string.widget_label_altitude),
                value = "${String.format(Locale.US, "%.0f", data.barAltitude)} m"
            )
            DataRow(
                label = context.getString(R.string.widget_label_pressure),
                value = "${String.format(Locale.US, "%.2f", data.pressureKpa)} kPa"
            )
            DataRow(
                label = "",
                value = "${String.format(Locale.US, "%.1f", data.pressureKpa * 7.50062)} mmHg"
            )
        }
    }

    /**
     * GPS section as its own nested Column. 5 children — also under
     * the 10-child cap.
     */
    @Composable
    private fun GpsSection(
        context: android.content.Context,
        data: WidgetDataStore.Snapshot,
        dimmed: Boolean
    ) {
        // The GPS line is carried forward from the last fix while the
        // barometer self-refreshes each tick, so it can be arbitrarily
        // old. Dim it once stale (parity with the iOS widget, which
        // applies .opacity(0.4)). Glance has no opacity modifier, so we
        // dim by darkening the row colors.
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)) {
            SectionLabel(context.getString(R.string.widget_section_gps))
            DataRow(
                label = context.getString(R.string.widget_label_altitude),
                value = "${String.format(Locale.US, "%.0f", data.gpsAltitude)} m",
                dimmed = dimmed
            )
            val speed = maxOf(data.gpsSpeed, 0.0)
            DataRow(
                label = context.getString(R.string.widget_label_speed),
                value = "${String.format(Locale.US, "%.1f", speed * 3.6)} km/h",
                dimmed = dimmed
            )
            DataRow(
                label = context.getString(R.string.widget_label_lat),
                value = "${String.format(Locale.US, "%.4f", data.gpsLat)}°",
                dimmed = dimmed
            )
            DataRow(
                label = context.getString(R.string.widget_label_lon),
                value = "${String.format(Locale.US, "%.4f", data.gpsLon)}°",
                dimmed = dimmed
            )
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text,
            style = TextStyle(
                color = androidx.glance.unit.ColorProvider(Color(0xFFFF9800)),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )
    }

    @Composable
    private fun DataRow(label: String, value: String, dimmed: Boolean = false) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                if (label.isNotEmpty()) label else "",
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(
                        if (dimmed) Color(0xFF555555) else Color(0xFF888888)
                    ),
                    fontSize = 9.sp
                ),
                modifier = GlanceModifier.width(52.dp)
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                value,
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(
                        if (dimmed) Color(0xFF777777) else Color(0xFFFFFFFF)
                    ),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }

    @Composable
    private fun Divider() {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF333333))
        ) {}
    }

    /** Localized relative age label ("12 min. ago" / "3 hr. ago") used
     *  in the header once the snapshot is stale, so a multi-hour-old
     *  reading is not mistaken for current data. Uses the platform's
     *  localized relative-time formatter (parity with iOS's
     *  `Text(recordDate, style: .relative)`). */
    private fun relativeAge(updatedAt: Long): String =
        DateUtils.getRelativeTimeSpanString(
            updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()

    companion object {
        /** Shared staleness threshold (ms). Beyond this age the snapshot
         *  is treated as stale. Kept in sync with the iOS widget
         *  (30 min). */
        private const val STALENESS_THRESHOLD_MS = 30L * 60L * 1000L
    }
}
