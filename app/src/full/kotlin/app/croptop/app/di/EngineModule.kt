package app.croptop.app.di

import android.content.Context
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.Engine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `full` flavor: the native engine (contract-tested). Decoding/encoding land in;
 * until then opening/saving files shows a friendly "can't be opened yet" state.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun engine(@ApplicationContext context: Context): Engine = NativeEngine(context.applicationContext)
}
