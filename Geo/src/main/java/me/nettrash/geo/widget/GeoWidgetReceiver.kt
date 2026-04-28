package me.nettrash.geo.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class GeoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = GeoWidget()
}
