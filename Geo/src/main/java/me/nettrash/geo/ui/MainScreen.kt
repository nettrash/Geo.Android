package me.nettrash.geo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.EntryPointAccessors
import me.nettrash.geo.R
import me.nettrash.geo.di.PermissionsEntryPoint
import me.nettrash.geo.permissions.PermissionsBanner
import me.nettrash.geo.ui.info.InfoScreen
import me.nettrash.geo.ui.map.MapScreen
import me.nettrash.geo.ui.nature.NatureScreen
import me.nettrash.geo.ui.stat.StatScreen

data class TabItem(val titleRes: Int, val icon: ImageVector)

@Composable
fun MainScreen(viewModel: GeoViewModel = hiltViewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        TabItem(R.string.tab_info, Icons.Default.Straighten),
        TabItem(R.string.tab_stat, Icons.AutoMirrored.Filled.ShowChart),
        TabItem(R.string.tab_map, Icons.Default.Map),
        TabItem(R.string.tab_nature, Icons.Default.Terrain)
    )

    // PermissionsMonitor is a Hilt @Singleton. The compose tree has no
    // direct Hilt injection point for non-ViewModel singletons, so we
    // pull it out of the application component via the entry-point
    // pattern. Cheap (a single map lookup) and lets us survive
    // configuration changes without rewiring.
    val context = LocalContext.current
    val permissionsMonitor = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PermissionsEntryPoint::class.java
        ).permissionsMonitor()
    }

    // Re-check permissions on every foreground — the user may have
    // toggled the switch from Settings while we were paused. Mirrors
    // the iOS `scenePhase == .active` re-check in MainView.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsMonitor.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // History-consuming surfaces (Stat / Map / Nature) pull a refresh
    // when shown; refreshIfNeeded() no-ops when the cache is clean, so
    // the per-insert dirty flag (mirroring iOS) avoids rebuilding the
    // graphs on every recorded sample while keeping the StateFlows the
    // screens collect up to date the moment a tab is opened.
    LaunchedEffect(selectedTab) {
        if (selectedTab != 0) {
            viewModel.refreshIfNeeded()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            PermissionsBanner(monitor = permissionsMonitor)
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1A1A1A)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val label = stringResource(tab.titleRes)
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFFF9800),
                            selectedTextColor = Color(0xFFFF9800),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFF2A2A2A)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> InfoScreen(viewModel = viewModel)
                1 -> StatScreen(viewModel = viewModel)
                2 -> MapScreen(viewModel = viewModel)
                3 -> NatureScreen(viewModel = viewModel)
            }
        }
    }
}
