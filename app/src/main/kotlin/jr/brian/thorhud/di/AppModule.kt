package jr.brian.thorhud.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jr.brian.thorhud.led.BifrostClient
import jr.brian.thorhud.led.DirectLedDriver
import jr.brian.thorhud.led.StickLights
import javax.inject.Singleton

/**
 * Hilt is wired for @Inject constructors in the Singleton scope. This module
 * exists as a stable anchor for @Provides bindings we add later (settings,
 * display router, telemetry).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Prefer the direct SN3112 writer on an AYN Thor; fall back to
     * Bifrost's broadcast API everywhere else. The direct driver's
     * constructor resolves PServerBinder once and reports back through
     * [DirectLedDriver.isAvailable], so this picks the real answer
     * without probing.
     */
    @Provides
    @Singleton
    fun provideStickLights(
        direct: DirectLedDriver,
        bifrost: BifrostClient,
    ): StickLights = if (direct.isAvailable()) direct else bifrost
}
