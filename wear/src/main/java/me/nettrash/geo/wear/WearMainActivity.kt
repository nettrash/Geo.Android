package me.nettrash.geo.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Scaffold

/**
 * Wear OS entry point. Mirrors iOS `Geo Watch App/GeoApp.swift` —
 * minimal Scene that hosts the single ContentView. Lifecycle binds
 * the barometer manager and snapshot store so the UI keeps the same
 * 1 Hz cadence as the iOS Watch.
 */
class WearMainActivity : ComponentActivity() {

    private val barometer by lazy { WearBarometerManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val pressure by barometer.pressure.collectAsState()
            val altitude by barometer.altitude.collectAsState()
            val everest by barometer.everest.collectAsState()
            val history by barometer.history.collectAsState()
            val inbound by WearSnapshotStore.token.collectAsState()

            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                WearGeoScreen(
                    pressureKpa = pressure,
                    altitudeMeters = altitude,
                    everestRatio = everest,
                    altitudeHistory = history,
                    inbound = inbound
                )
            }
        }
    }

    // Sample only while the UI is actually visible. Tying start/stop
    // to onStart/onStop (rather than onCreate/onDestroy) pauses the
    // pressure sensor when the activity is merely stopped — screen off
    // or wrist down — instead of leaving it registered for the whole
    // process lifetime, which needlessly drains a wearable battery.
    override fun onStart() {
        super.onStart()
        barometer.start()
    }

    override fun onStop() {
        super.onStop()
        barometer.stop()
    }
}
