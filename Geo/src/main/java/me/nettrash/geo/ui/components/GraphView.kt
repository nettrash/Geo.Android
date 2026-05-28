package me.nettrash.geo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.data.model.DataItem
import me.nettrash.geo.data.model.DataPoint
import me.nettrash.geo.data.model.GraphLine

@Composable
fun GeoGraphView(
    caption: String,
    data: List<DataItem>,
    lines: List<GraphLine>,
    min: Float,
    max: Float,
    measurement: String = "km"
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    val lineRange = max - min

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
            .padding(8.dp)
    ) {
        Text(
            text = caption,
            fontSize = 18.sp,
            color = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.align(Alignment.Center).rotate(-25f),
            fontWeight = FontWeight.Bold
        )

        Column {
            // Labels
            Text(
                "${max.toInt()} $measurement",
                style = labelStyle,
                modifier = Modifier.padding(start = 4.dp)
            )

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                val graphWidth = size.width
                val graphHeight = size.height

                // Draw axes
                drawLine(Color.White, Offset(0f, 0f), Offset(0f, graphHeight), strokeWidth = 0.5f)
                drawLine(Color.White, Offset(0f, graphHeight), Offset(graphWidth, graphHeight), strokeWidth = 0.5f)

                // Draw grid lines (25%, 50%, 75%)
                for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                    val y = graphHeight * (1f - frac)
                    drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(graphWidth, y), strokeWidth = 0.25f)
                }

                // Draw reference lines
                for (line in lines) {
                    if (line.value > min && line.value < max && lineRange > 0) {
                        val y = graphHeight * (1f - (line.value - min) / lineRange)
                        drawLine(Color(line.color), Offset(0f, y), Offset(graphWidth, y), strokeWidth = 1f)
                    }
                }

                // Draw data line
                if (data.size >= 2 && lineRange > 0) {
                    val path = Path()
                    val step = graphWidth / (data.size - 1).coerceAtLeast(1)
                    data.forEachIndexed { idx, item ->
                        val x = idx * step
                        val y = graphHeight * (1f - (item.value - min) / lineRange)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx()))

                    // Vertices. Use dp-converted-to-px so high-DPI
                    // screens (Pixel 8 Pro is ~480ppi, density ~3.0)
                    // render visible dots — `radius = 2f` was 2
                    // pixels = ⅔ dp, basically invisible.
                    val vertexRadius = 3.dp.toPx()
                    data.forEachIndexed { idx, item ->
                        val x = idx * step
                        val y = graphHeight * (1f - (item.value - min) / lineRange)
                        drawCircle(Color.White, radius = vertexRadius, center = Offset(x, y))
                    }
                }
            }

            // Bottom labels
            if (data.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(
                        data.first().legend,
                        style = labelStyle,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    Text(
                        data.last().legend,
                        style = labelStyle,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            Text(
                "${min.toInt()} $measurement",
                style = labelStyle,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
fun GeoGraphPointsView(
    caption: String,
    data: List<DataPoint>,
    lines: List<GraphLine>,
    min: Float,
    max: Float,
    colors: List<Color>,
    legend: List<String>,
    measurement: String = "km"
) {
    val labelStyle = TextStyle(color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    val lineRange = max - min

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
            .padding(8.dp)
    ) {
        Text(
            text = caption,
            fontSize = 18.sp,
            color = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.align(Alignment.Center).rotate(-25f),
            fontWeight = FontWeight.Bold
        )

        Column {
            // Legend
            if (legend.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp)) {
                    Column(modifier = Modifier.align(Alignment.TopEnd)) {
                        legend.forEachIndexed { idx, name ->
                            val color = if (idx < colors.size) colors[idx] else Color.White
                            Text(name, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Text(
                "${max.toInt()} $measurement",
                style = labelStyle,
                modifier = Modifier.padding(start = 4.dp)
            )

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                val graphWidth = size.width
                val graphHeight = size.height

                // Axes
                drawLine(Color.White, Offset(0f, 0f), Offset(0f, graphHeight), strokeWidth = 0.5f)
                drawLine(Color.White, Offset(0f, graphHeight), Offset(graphWidth, graphHeight), strokeWidth = 0.5f)

                // Grid
                for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                    val y = graphHeight * (1f - frac)
                    drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(graphWidth, y), strokeWidth = 0.25f)
                }

                // Reference lines
                for (line in lines) {
                    if (line.value > min && line.value < max && lineRange > 0) {
                        val y = graphHeight * (1f - (line.value - min) / lineRange)
                        drawLine(Color(line.color), Offset(0f, y), Offset(graphWidth, y), strokeWidth = 1f)
                    }
                }

                // Data lines + per-series vertex dots. The iOS
                // tracking chart draws filled circles at each sample
                // so the user can see exactly when each reading
                // landed — without them the line just feels like a
                // smooth gradient.
                if (data.size >= 2 && lineRange > 0) {
                    val numSeries = data.first().values.size
                    val step = graphWidth / (data.size - 1).coerceAtLeast(1)
                    val strokeWidth = 2.dp.toPx()
                    val vertexRadius = 3.dp.toPx()

                    for (seriesIdx in 0 until numSeries) {
                        val color = if (seriesIdx < colors.size) colors[seriesIdx] else Color.White
                        val path = Path()
                        data.forEachIndexed { idx, point ->
                            val value = if (seriesIdx < point.values.size) point.values[seriesIdx] else 0f
                            val x = idx * step
                            val y = graphHeight * (1f - (value - min) / lineRange)
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = strokeWidth))

                        // Vertices for this series — filled circles
                        // in the same colour as the line.
                        data.forEachIndexed { idx, point ->
                            val value = if (seriesIdx < point.values.size) point.values[seriesIdx] else 0f
                            val x = idx * step
                            val y = graphHeight * (1f - (value - min) / lineRange)
                            drawCircle(color, radius = vertexRadius, center = Offset(x, y))
                        }
                    }
                }
            }

            if (data.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(
                        data.first().legend,
                        style = labelStyle,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    Text(
                        data.last().legend,
                        style = labelStyle,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            Text(
                "${min.toInt()} $measurement",
                style = labelStyle,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
