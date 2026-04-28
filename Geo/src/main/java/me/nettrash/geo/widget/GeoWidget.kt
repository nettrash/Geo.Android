package me.nettrash.geo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import me.nettrash.geo.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataStore.read(context)
        provideContent {
            GlanceTheme {
                WidgetContent(data)
            }
        }
    }

    @Composable
    private fun WidgetContent(data: WidgetDataStore.Snapshot) {
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
                modifier = GlanceModifier
                    .fillMaxSize()
            )

            // ── Foreground content ───────────────────────────────────────
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {

                // ── Header ───────────────────────────────────────────────
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        "GEO",
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

                Spacer(GlanceModifier.height(6.dp))
                Divider()
                Spacer(GlanceModifier.height(6.dp))

                // ── Barometer section ─────────────────────────────────────
                SectionLabel("BAROMETER")
                Spacer(GlanceModifier.height(3.dp))
                DataRow(
                    label = "Altitude",
                    value = "${String.format(Locale.US, "%.0f", data.barAltitude)} m"
                )
                DataRow(
                    label = "Pressure",
                    value = "${String.format(Locale.US, "%.2f", data.pressureKpa)} kPa"
                )
                DataRow(
                    label = "",
                    value = "${String.format(Locale.US, "%.1f", data.pressureKpa * 7.50062)} mmHg"
                )

                Spacer(GlanceModifier.height(6.dp))
                Divider()
                Spacer(GlanceModifier.height(6.dp))

                // ── GPS section ───────────────────────────────────────────
                SectionLabel("GPS")
                Spacer(GlanceModifier.height(3.dp))
                DataRow(
                    label = "Altitude",
                    value = "${String.format(Locale.US, "%.0f", data.gpsAltitude)} m"
                )
                val speed = maxOf(data.gpsSpeed, 0.0)
                DataRow(
                    label = "Speed",
                    value = "${String.format(Locale.US, "%.1f", speed * 3.6)} km/h"
                )
                DataRow(
                    label = "Lat",
                    value = "${String.format(Locale.US, "%.4f", data.gpsLat)}°"
                )
                DataRow(
                    label = "Lon",
                    value = "${String.format(Locale.US, "%.4f", data.gpsLon)}°"
                )
            }
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
