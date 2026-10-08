package app.croptop.app.di

import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.fake.FakeEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `fake` flavor engine binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun engine(): Engine = FakeEngine()
}
