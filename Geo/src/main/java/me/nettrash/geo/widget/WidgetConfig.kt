package me.nettrash.geo.widget

import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Per-instance widget configuration. Mirrors iOS
 * `Widget/AppIntent.swift` — two booleans controlling which sections
 * the widget displays. Stored via Glance's preferences-backed state
 * so each pinned widget remembers its own choice.
 */
object WidgetConfig {

    val KEY_SHOW_BAROMETER = booleanPreferencesKey("show_barometer")
    val KEY_SHOW_GPS       = booleanPreferencesKey("show_gps")

    /** State definition handed to Glance so the widget composition
     *  reads the per-instance preferences. */
    val stateDefinition: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition

    /** Defaults match iOS — both on. */
    fun showBarometer(prefs: Preferences): Boolean = prefs[KEY_SHOW_BAROMETER] ?: true
    fun showGps(prefs: Preferences): Boolean = prefs[KEY_SHOW_GPS] ?: true

    fun MutablePreferences.setShowBarometer(value: Boolean) {
        this[KEY_SHOW_BAROMETER] = value
    }

    fun MutablePreferences.setShowGps(value: Boolean) {
        this[KEY_SHOW_GPS] = value
    }
}
