package app.croptop.app.di

import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.ShortcutManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InputModule {
    /** One keymap for the whole app; user remaps apply to every window. */
    @Provides
    @Singleton
    fun shortcuts(): ShortcutManager = ShortcutManager()
}

@Module
@InstallIn(ActivityRetainedComponent::class)
object WindowInputModule {
    /** One dispatcher per window: each window has its own active screen. */
    @Provides
    @ActivityRetainedScoped
    fun dispatcher(shortcuts: ShortcutManager): ActionDispatcher = ActionDispatcher(shortcuts)
}
