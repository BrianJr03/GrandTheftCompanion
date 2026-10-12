package jr.brian.thorhud.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jr.brian.thorhud.led.BifrostClient
import jr.brian.thorhud.led.StickLights

/**
 * Hilt is wired for @Inject constructors in the Singleton scope. This module
 * exists as a stable anchor for @Provides bindings we add later (settings,
 * display router, telemetry).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** The thumbstick lights are driven through Bifrost. */
    @Provides
    fun provideStickLights(bifrost: BifrostClient): StickLights = bifrost
}
