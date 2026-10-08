package app.croptop.app.di

import app.croptop.app.files.FileProviderShareCache
import app.croptop.feature.editor.files.ShareCache
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FilesModule {
    @Binds
    abstract fun shareCache(impl: FileProviderShareCache): ShareCache
}
