package me.nettrash.geo.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.MountainList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MountainLoader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): MountainData? {
        return try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("list", "raw", context.packageName)
            )
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val data = json.decodeFromString<MountainData>(jsonString)
            // Inject the asset-folder context each mountain came
            // from. The JSON only carries filenames; the actual asset
            // lives under `mountains/<list-folder>/<filename>` and
            // those folder names don't appear in the JSON.
            data.populateImageAssetPaths()
            data
        } catch (e: Exception) {
            AppLog.app.warn("Failed to load mountains list", e)
            null
        }
    }

    private fun MountainData.populateImageAssetPaths() {
        highest?.populateImageAssetPaths("mountains/highest")
        sevenPeaks?.populateImageAssetPaths("mountains/seven_peaks")
        snowLeopardOfRussia?.populateImageAssetPaths("mountains/snow_leopard")
    }

    private fun MountainList.populateImageAssetPaths(folder: String) {
        mountains?.forEach { mountain ->
            val image = mountain.image?.takeIf { it.isNotBlank() } ?: return@forEach
            mountain.imageAssetPath = "$folder/$image"
        }
    }
}
