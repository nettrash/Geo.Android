package me.nettrash.geo.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.nettrash.geo.R
import me.nettrash.geo.ui.theme.GeoTheme

/**
 * Configuration screen shown when the user drops a Geo widget on
 * their home screen, OR taps the existing widget's reconfigure
 * affordance. Mirrors iOS `Widget/AppIntent.swift` — two switches
 * controlling which sections are rendered.
 *
 * Wired in via `appwidget-provider/@android:configure` in
 * `res/xml/geo_widget_info.xml`.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cancel result by default — the system removes the widget
        // when the user backs out without confirming.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            GeoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConfigUi(
                        appWidgetId = appWidgetId,
                        onConfirm = { showBarometer, showGps ->
                            saveConfig(showBarometer, showGps)
                        }
                    )
                }
            }
        }
    }

    private fun saveConfig(showBarometer: Boolean, showGps: Boolean) {
        val context = applicationContext
        // Need a coroutine because the Glance state APIs are suspend
        // — use lifecycleScope so it cancels if the activity dies
        // mid-save.
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(context)
            val glanceId = manager.getGlanceIdBy(appWidgetId)
            // `updateAppWidgetState`'s update block receives the
            // current Preferences and must return the new one. We
            // build a fresh MutablePreferences (which IS a
            // Preferences) carrying our two booleans.
            updateAppWidgetState(
                context = context,
                definition = WidgetConfig.stateDefinition,
                glanceId = glanceId
            ) { prefs ->
                mutablePreferencesOf().apply {
                    // Preserve anything else stored in the prefs.
                    prefs.asMap().forEach { (key, value) ->
                        @Suppress("UNCHECKED_CAST")
                        set(key as androidx.datastore.preferences.core.Preferences.Key<Any>, value)
                    }
                    set(WidgetConfig.KEY_SHOW_BAROMETER, showBarometer)
                    set(WidgetConfig.KEY_SHOW_GPS, showGps)
                }
            }
            GeoWidget().updateAll(context)

            val resultValue = Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId
            )
            setResult(Activity.RESULT_OK, resultValue)
            finish()
        }
    }
}

@Composable
private fun ConfigUi(
    appWidgetId: Int,
    onConfirm: (showBarometer: Boolean, showGps: Boolean) -> Unit
) {
    val context = LocalContext.current

    // On reconfigure the same activity is reused to edit an existing
    // widget, so seed the switches from this instance's persisted prefs
    // rather than hardcoding ON — otherwise confirming would silently
    // re-enable a section the user had turned off. Defaults to ON only
    // when nothing was saved (new widget). Mirrors iOS, where WidgetKit
    // re-presents the AppIntent's stored parameter values.
    val saved by produceState<Pair<Boolean, Boolean>?>(initialValue = null, appWidgetId) {
        value = runCatching {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            val prefs = getAppWidgetState(
                context = context,
                definition = WidgetConfig.stateDefinition,
                glanceId = glanceId
            )
            WidgetConfig.showBarometer(prefs) to WidgetConfig.showGps(prefs)
        }.getOrNull() ?: (true to true)
    }

    // Wait until the persisted prefs have loaded before showing the
    // switches so they never flash the wrong (hardcoded) state.
    val loaded = saved ?: return

    var showBarometer by remember(loaded) { mutableStateOf(loaded.first) }
    var showGps by remember(loaded) { mutableStateOf(loaded.second) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.widget_config_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.widget_config_subtitle),
            fontSize = 13.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(20.dp))

        ConfigSwitch(
            label = stringResource(R.string.widget_config_show_barometer),
            checked = showBarometer,
            onChange = { showBarometer = it }
        )
        ConfigSwitch(
            label = stringResource(R.string.widget_config_show_gps),
            checked = showGps,
            onChange = { showGps = it }
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onConfirm(showBarometer, showGps) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(stringResource(R.string.widget_config_confirm), color = Color.White)
        }
    }
}

@Composable
private fun ConfigSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFFFF9800)
            )
        )
    }
}
