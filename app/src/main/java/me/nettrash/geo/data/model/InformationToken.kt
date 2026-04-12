package me.nettrash.geo.data.model

import kotlinx.serialization.Serializable

@Serializable
data class InformationToken(
    val recordDate: Long = 0L,
    val gpsAltitude: Double = 0.0,
    val gpsSpeed: Double = 0.0,
    val barPressure: Double = 0.0,
    val barAltitude: Double = 0.0
)
