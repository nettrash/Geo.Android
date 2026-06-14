package me.nettrash.geo.ui.stat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import me.nettrash.geo.R
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
