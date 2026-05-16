package me.nettrash.geo.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.nettrash.geo.permissions.PermissionsMonitor

/**
 * Composables can't `@Inject` into themselves. Use this entry point to
 * pull `@Singleton`-scoped objects out of the application component
 * without going through a ViewModel.
 *
 * Usage:
 * ```
 * EntryPointAccessors.fromApplication(
 *     context.applicationContext, PermissionsEntryPoint::class.java
 * ).permissionsMonitor()
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PermissionsEntryPoint {
    fun permissionsMonitor(): PermissionsMonitor
}
