package me.nettrash.geo.util

import android.util.Log
import me.nettrash.geo.BuildConfig

/**
 * Centralised tagged logger. Mirrors iOS `Core/AppLog.swift` so the
 * two codebases use the same subsystem categories when reading logs.
 *
 * Each category prefixes its tag with `Geo:` so logcat filters on the
 * iOS subsystem `me.nettrash.Geo` map cleanly to `Geo:*`.
 *
 * On release builds (BuildConfig.DEBUG == false) `debug()` is a no-op
 * so verbose tracing doesn't survive into production binaries.
 */
// Not `sealed`: the `object : AppLog(…) {}` instances below need an
// open class. Each category is still effectively a singleton because
// the constructor is internal to this file and only used by those
// companion-object lines.
abstract class AppLog(category: String) {
    private val tag = "Geo:$category"

    fun debug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun info(msg: String) {
        Log.i(tag, msg)
    }

    fun warn(msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    }

    fun error(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }

    companion object {
        val app          = object : AppLog("app") {}
        val location     = object : AppLog("location") {}
        val barometer    = object : AppLog("barometer") {}
        val widget       = object : AppLog("widget") {}
        val watch        = object : AppLog("watch") {}
        val connectivity = object : AppLog("connectivity") {}
        val ar           = object : AppLog("ar") {}
        val history      = object : AppLog("history") {}
        val background   = object : AppLog("background") {}
    }
}
