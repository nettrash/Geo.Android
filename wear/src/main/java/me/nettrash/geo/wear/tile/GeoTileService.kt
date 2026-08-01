package me.nettrash.geo.wear.tile

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.TypeBuilders.FloatProp
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_LEFT
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.nettrash.geo.wear.Atmosphere
import me.nettrash.geo.wear.WearSnapshotStore
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Wear Tile that mirrors iOS `AltitudeRectangularView`:
 *
 *   ┌───────────────────────┐
 *   │ GEO                   │
 *   │ 4,521 m               │  (altitude, large)
 *   │ 60.18 kPa             │  (pressure, small)
 *   │ ▒▒▒▒▒▒░░░░░░░░░░░     │  (gauge, 0..8848 m)
 *   └───────────────────────┘
 *
 * Data sources in priority order (matches the iOS Watch widget):
 *   1. Live barometer sample taken in `onTileRequest` (timeout 5s).
 *   2. Last phone-pushed snapshot from [WearSnapshotStore] — exists
 *      whenever the paired iPhone has sent us a token via
 *      WearableSnapshotListener.
 *
 * Tile freshness is set to [FRESHNESS_MS] so the system re-invokes us
 * at a reasonable cadence without burning watch battery. The Watch's
 * tile cache also re-renders on demand when the user actually swipes
 * to it — which is the only moment freshness is actually observable,
 * and that path is unaffected by the interval.
 */
class GeoTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> =
        tileScope.future {
            val data = collectData(this@GeoTileService)
            val deviceParams = requestParams.deviceConfiguration
            buildTile(this@GeoTileService, deviceParams, data)
        }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        tileScope.future {
            // No bitmap resources — the tile is pure text + gauge.
            Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any in-flight coroutine when the service is torn
        // down so we don't leak the executor.
        tileScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"

        /**
         * Background refresh cadence requested from the system.
         *
         * Every invocation starts this service's process and powers up the
         * pressure sensor for a live sample (see [collectData]) — on the
         * smallest battery in the system. At 5 minutes that was ~288 wakeups a
         * day for a tile the user may never look at.
         *
         * 15 minutes matches BarometerRefreshWorker on the phone and the iOS
         * widget/complication policies. Nothing observable changes: swiping to
         * the tile triggers an on-demand `onTileRequest` that takes a fresh
         * sample regardless of this interval, and a phone-pushed snapshot still
         * arrives via WearableSnapshotListener in between.
         */
        private const val FRESHNESS_MS: Long = 15 * 60 * 1000L
    }

    // ─── Data ─────────────────────────────────────────────────────

    private data class TileData(
        val pressureKpa: Double,
        val altitudeMeters: Double,
        val isFromBarometer: Boolean
    )

    private suspend fun collectData(context: Context): TileData {
        // 1. Try a fresh barometer sample.
        val pressureHpa = sampleBarometer(context)
        if (pressureHpa != null) {
            // Clamp the live sample to a plausible barometric range (#11).
            val pressureKpa = (pressureHpa / 10.0).coerceIn(30.0, 110.0)
            // Prefer the phone's calibration reference (same formula as
            // WearBarometerManager.onSensorChanged) so the tile agrees
            // with the in-app reading and the iOS tile; fall back to
            // standard atmosphere only when no calibrated reference
            // exists. `barPreassure` is the misspelled on-wire field.
            val token = WearSnapshotStore.token.value
            val altitude = if (token != null && token.barPreassure > 0) {
                token.barAltitude +
                    (Atmosphere.altitude(pressureKpa) - Atmosphere.altitude(token.barPreassure))
            } else {
                Atmosphere.altitude(pressureKpa)
            }
            return TileData(pressureKpa, altitude, isFromBarometer = true)
        }
        // 2. Fall back to the last phone-pushed snapshot.
        val token = WearSnapshotStore.token.value
        if (token != null && token.barPreassure > 0) {
            return TileData(token.barPreassure, token.barAltitude, isFromBarometer = false)
        }
        // 3. Nothing yet — render zeros.
        return TileData(0.0, 0.0, isFromBarometer = false)
    }

    private suspend fun sampleBarometer(context: Context): Float? {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<Float> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
                            sensorManager.unregisterListener(this)
                            cont.resume(event.values[0])
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager.registerListener(
                    listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL
                )
                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        }
    }

    // ─── Layout ───────────────────────────────────────────────────

    private fun buildTile(
        context: Context,
        deviceParams: DeviceParametersBuilders.DeviceParameters?,
        data: TileData
    ): Tile {
        val altitudeText = String.format(Locale.US, "%.0f m", data.altitudeMeters)
        val pressureText = String.format(Locale.US, "%.2f kPa", data.pressureKpa)
        val gaugeFraction = (data.altitudeMeters / Atmosphere.EVEREST_HEIGHT_M)
            .coerceIn(0.0, 1.0)
            .toFloat()

        // Hand-rolled padding+expand layouts kept rendering past the
        // bezel on round watches because we couldn't reliably guess
        // the tile's *actual* usable area from DeviceParameters.
        // Material's `PrimaryLayout` exists precisely for this: it
        // inscribes content into the round display's safe square and
        // arranges primary label / content / secondary label slots
        // for us. Falling back to deviceParams supplied by the
        // request guarantees the inscription matches *this* watch's
        // geometry rather than our own arithmetic.
        val params = deviceParams ?: return emptyTile()

        // Primary label = brand mark. Small text, top of safe area.
        val primaryLabel = Text.Builder()
            .setText("GEO")
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(11f))
                    .setColor(argb(0xFFFF9800.toInt()))
                    .build()
            )
            .build()

        // Secondary label = data-source badge. Small text, bottom of safe area.
        val secondaryLabel = Text.Builder()
            .setText(if (data.isFromBarometer) "live" else "phone")
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(9f))
                    .setColor(argb(0xFF80DEEA.toInt()))
                    .build()
            )
            .build()

        // Main content area — altitude / pressure / gauge stacked.
        val content = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Text.Builder()
                    .setText(altitudeText)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(22f))
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(
                Text.Builder()
                    .setText(pressureText)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(0xFFBDBDBD.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(buildGauge(gaugeFraction))
            .build()

        val layout = Layout.Builder()
            .setRoot(
                PrimaryLayout.Builder(params)
                    .setPrimaryLabelTextContent(primaryLabel)
                    .setContent(content)
                    .setSecondaryLabelTextContent(secondaryLabel)
                    .build()
            )
            .build()

        return Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(
                Timeline.Builder()
                    .addTimelineEntry(
                        TimelineEntry.Builder().setLayout(layout).build()
                    )
                    .build()
            )
            .build()
    }

    /** Last-resort tile when the system gives us no DeviceParameters. */
    private fun emptyTile(): Tile = Tile.Builder()
        .setResourcesVersion(RESOURCES_VERSION)
        .setFreshnessIntervalMillis(FRESHNESS_MS)
        .setTileTimeline(
            Timeline.Builder()
                .addTimelineEntry(
                    TimelineEntry.Builder()
                        .setLayout(
                            Layout.Builder()
                                .setRoot(
                                    Text.Builder()
                                        .setText("GEO")
                                        .setFontStyle(
                                            FontStyle.Builder()
                                                .setSize(sp(14f))
                                                .setColor(argb(0xFFFF9800.toInt()))
                                                .build()
                                        )
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .build()

    /**
     * Tiny coloured progress bar. ProtoLayout has no Slider, so we
     * draw a filled `Box` inside a track `Box`.
     *
     * The track uses `expand()` so it stretches to whatever width
     * the parent Column allows; the fill is sized as a percentage of
     * the *parent's* width via `expand(weight)`. This dodges the
     * old approach's fragility (hardcoded dp guesses based on
     * screen size that broke on round watches).
     */
    private fun buildGauge(fraction: Float): Box {
        // Colour interpolates green → orange → red as altitude rises.
        val gaugeColor = when {
            fraction < 0.5f -> 0xFF4CAF50.toInt()  // green
            fraction < 0.9f -> 0xFFFF9800.toInt()  // orange
            else            -> 0xFFE57373.toInt()  // red
        }

        // The track is a Row that fills the parent width; inside it,
        // a filled Box claims `fraction` of the row's width via
        // `setLayoutWeight(fraction)`, and a spacer Box claims the
        // rest via `setLayoutWeight(1 - fraction)`. (ProtoLayout's
        // `expand()` takes no arguments; weighted expansion goes
        // through `ExpandedDimensionProp.Builder.setLayoutWeight`.)
        val fillWeight = fraction.coerceIn(0.001f, 1f)
        val emptyWeight = (1f - fillWeight).coerceAtLeast(0.001f)

        val track = androidx.wear.protolayout.LayoutElementBuilders.Row.Builder()
            .setWidth(expand())
            .setHeight(dp(6f))
            // Filled portion.
            .addContent(
                Box.Builder()
                    .setWidth(weightedExpand(fillWeight))
                    .setHeight(dp(6f))
                    .setModifiers(
                        Modifiers.Builder()
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(gaugeColor))
                                    .setCorner(Corner.Builder().setRadius(dp(3f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            // Empty portion.
            .addContent(
                Box.Builder()
                    .setWidth(weightedExpand(emptyWeight))
                    .setHeight(dp(6f))
                    .setModifiers(
                        Modifiers.Builder()
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(0xFF303030.toInt()))
                                    .setCorner(Corner.Builder().setRadius(dp(3f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        // Wrap the row in a Box so the gauge has a single root
        // element the parent Column can lay out simply.
        return Box.Builder()
            .setWidth(expand())
            .setHeight(dp(6f))
            .addContent(track)
            .build()
    }

    /**
     * Helper: build an `ExpandedDimensionProp` with a layout weight.
     * ProtoLayout 1.x's `expand()` factory takes no arguments, so
     * weight-based row/column sizing has to go through the builder.
     */
    private fun weightedExpand(weight: Float): DimensionBuilders.ExpandedDimensionProp =
        DimensionBuilders.ExpandedDimensionProp.Builder()
            .setLayoutWeight(FloatProp.Builder(weight).build())
            .build()
}
