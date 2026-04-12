package me.nettrash.geo.data.model

data class DataItem(
    val value: Float,
    val legend: String
)

data class DataPoint(
    val values: List<Float>,
    val legend: String
)

data class GraphLine(
    val value: Float,
    val text: String,
    val color: Long // ARGB color
)
