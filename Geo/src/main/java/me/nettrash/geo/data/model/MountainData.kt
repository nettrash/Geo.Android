package me.nettrash.geo.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
) {
    /**
     * Full assets-folder path to the bundled photo for this peak,
     * e.g. `"mountains/highest/2.jpg"`. Computed by [MountainLoader]
     * after JSON deserialization because the path depends on which
     * list the mountain came from (highest / sevenPeaks /
     * snowLeopardOfRussia), and the JSON itself only carries the
     * filename. `null` if the mountain has no `image` field.
     *
     * `@Transient` so kotlinx-serialization doesn't try to encode /
     * decode it — it's a derived field only ever set in-process.
     */
    @Transient
    var imageAssetPath: String? = null
}

@Serializable
data class MountainCoordinates(
    val latitude: Double? = null,
    val longitude: Double? = null
)
