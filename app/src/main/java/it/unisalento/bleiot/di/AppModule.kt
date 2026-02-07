package it.unisalento.bleiot.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Standard classes with @Inject constructor() don't need @Provides here
}