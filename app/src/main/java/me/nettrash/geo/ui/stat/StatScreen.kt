package me.nettrash.geo.ui.stat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.data.model.GraphLine
import me.nettrash.geo.ui.GeoViewModel
import me.nettrash.geo.ui.components.GeoGraphPointsView
import me.nettrash.geo.ui.components.GeoGraphView

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

    val thinAirLine = GraphLine(4500f, "thin air", 0xFFFFFF00)
    val deathZoneLine = GraphLine(7980f, "death zone", 0xFFFF0000)
    val seaLevelLine = GraphLine(0f, "sea level", 0xFF0000FF)
    val normalPressureLine = GraphLine(760f, "normal", 0xFF4CAF50)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "TRACKING",
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
            caption = "TRACKING ALTITUDE",
            data = trackingData,
            lines = trackingLines,
            min = trackingMin,
            max = trackingMax,
            colors = listOf(Color.White, Color(0xFFFF9800)),
            legend = listOf("barometer", "gps"),
            measurement = "m"
        )

        Text(
            "STATISTICS",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )

        GeoGraphView(
            caption = "PRESSURE",
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
            caption = "ALTITUDE BAROMETER",
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
            caption = "ALTITUDE GPS",
            data = gpsAltData,
            lines = gpsLines,
            min = gpsAltMin,
            max = gpsAltMax,
            measurement = "m"
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
