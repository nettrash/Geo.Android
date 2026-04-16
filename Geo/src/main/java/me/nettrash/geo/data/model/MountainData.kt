package me.nettrash.geo.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MountainData(
    val highest: MountainList? = null,
    val sevenPeaks: MountainList? = null,
    val snowLeopardOfRussia: MountainList? = null
)

@Serializable
data class MountainList(
    val source: String? = null,
    val columns: List<String>? = null,
    val mountains: List<MountainInfo>? = null
)

@Serializable
data class MountainInfo(
    val position: Int? = null,
    val image: String? = null,
    @SerialName("partOfTheWorld")
    val partOfTheWorld: String? = null,
    val name: String? = null,
    val height: Int? = null,
    val location: String? = null,
    val country: String? = null,
    val coordinates: MountainCoordinates? = null,
    val relativeHeight: Int? = null,
    val parent: String? = null,
    val firstAscent: String? = null,
    val ascents: Int? = null,
    val attemptsToAscend: Int? = null
)

@Serializable
data class MountainCoordinates(
    val latitude: Double? = null,
    val longitude: Double? = null
)
