package me.nettrash.geo.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import me.nettrash.geo.data.model.MountainData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MountainLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): MountainData? {
        return try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("list", "raw", context.packageName)
            )
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<MountainData>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
