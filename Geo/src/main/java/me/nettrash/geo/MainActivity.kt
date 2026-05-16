package me.nettrash.geo

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import me.nettrash.geo.permissions.PermissionsMonitor
import me.nettrash.geo.ui.MainScreen
import me.nettrash.geo.ui.theme.GeoTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var permissionsMonitor: PermissionsMonitor

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Always refresh so the PermissionsBanner can react instantly
        // whether the user granted or denied. Mirrors iOS
        // CLLocationManager.didChangeAuthorization.
        permissionsMonitor.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mark the prompt as shown *before* launching it so a
        // subsequent denial is visible to PermissionsMonitor on the
        // next refresh. (PermissionsMonitor.markLocationPromptShown
        // also calls refresh() internally.)
        permissionsMonitor.markLocationPromptShown()

        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        setContent {
            GeoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
