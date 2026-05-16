package me.nettrash.geo.widget

import android.content.Context
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
                    val timeStr = if (data.updatedAt > 0L)
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.updatedAt))
                    else "--:--"
                    Text(
                        timeStr,
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(Color(0xFF888888)),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }

                Divider()

                if (showBarometer) {
                    BarometerSection(context, data)
                }

                if (showBarometer && showGps) {
                    Divider()
                }

                if (showGps) {
                    GpsSection(context, data)
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
        data: WidgetDataStore.Snapshot
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)) {
            SectionLabel(context.getString(R.string.widget_section_gps))
            DataRow(
                label = context.getString(R.string.widget_label_altitude),
                value = "${String.format(Locale.US, "%.0f", data.gpsAltitude)} m"
            )
            val speed = maxOf(data.gpsSpeed, 0.0)
            DataRow(
                label = context.getString(R.string.widget_label_speed),
                value = "${String.format(Locale.US, "%.1f", speed * 3.6)} km/h"
            )
            DataRow(
                label = context.getString(R.string.widget_label_lat),
                value = "${String.format(Locale.US, "%.4f", data.gpsLat)}°"
            )
            DataRow(
                label = context.getString(R.string.widget_label_lon),
                value = "${String.format(Locale.US, "%.4f", data.gpsLon)}°"
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
    private fun DataRow(label: String, value: String) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                if (label.isNotEmpty()) label else "",
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(Color(0xFF888888)),
                    fontSize = 9.sp
                ),
                modifier = GlanceModifier.width(52.dp)
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                value,
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(Color(0xFFFFFFFF)),
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
}
