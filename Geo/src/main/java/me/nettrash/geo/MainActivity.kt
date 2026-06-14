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
import me.nettrash.geo.location.LocationManager
import me.nettrash.geo.permissions.PermissionsMonitor
import me.nettrash.geo.ui.MainScreen
import me.nettrash.geo.ui.theme.GeoTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var permissionsMonitor: PermissionsMonitor

    // The @Singleton LocationManager the ViewModel also uses. Injected
    // here so we can (re)start location updates the moment the grant
    // arrives — on a fresh install the ViewModel's init runs
    // startLocationUpdates() before the dialog is answered, so without
    // this nothing re-subscribes until the process is killed.
    @Inject lateinit var locationManager: LocationManager

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Always refresh so the PermissionsBanner can react instantly
        // whether the user granted or denied. Mirrors iOS
        // CLLocationManager.didChangeAuthorization.
        permissionsMonitor.refresh()

        // Re-subscribe to location updates if the grant just flipped a
        // location permission on. startLocationUpdates() is idempotent
        // and permission-guarded, so a denial here is a safe no-op.
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            locationManager.startLocationUpdates()
        }
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
