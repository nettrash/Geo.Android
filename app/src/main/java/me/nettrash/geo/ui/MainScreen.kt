package me.nettrash.geo.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import me.nettrash.geo.ui.info.InfoScreen
import me.nettrash.geo.ui.map.MapScreen
import me.nettrash.geo.ui.nature.NatureScreen
import me.nettrash.geo.ui.stat.StatScreen

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun MainScreen(viewModel: GeoViewModel = hiltViewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        TabItem("Info", Icons.Default.Straighten),
        TabItem("Stat", Icons.AutoMirrored.Filled.ShowChart),
        TabItem("Map", Icons.Default.Map),
        TabItem("Nature", Icons.Default.Terrain)
    )

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1A1A1A)
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
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
        when (selectedTab) {
            0 -> InfoScreen(modifier = Modifier.padding(paddingValues), viewModel = viewModel)
            1 -> StatScreen(modifier = Modifier.padding(paddingValues), viewModel = viewModel)
            2 -> MapScreen(modifier = Modifier.padding(paddingValues), viewModel = viewModel)
            3 -> NatureScreen(modifier = Modifier.padding(paddingValues), viewModel = viewModel)
        }
    }
}
