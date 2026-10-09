package jr.brian.thorhud.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt is wired for @Inject constructors in the Singleton scope. This module
 * exists as a stable anchor for @Provides bindings we add later (settings,
 * display router, telemetry).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
