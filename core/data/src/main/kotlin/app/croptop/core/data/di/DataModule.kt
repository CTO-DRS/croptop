package app.croptop.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.croptop.core.data.ContentResolverUriGrants
import app.croptop.core.data.DataStoreGradientRepository
import app.croptop.core.data.DataStorePreferencesRepository
import app.croptop.core.data.GradientRepository
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.data.RoomRecentDocumentsRepository
import app.croptop.core.data.UriGrants
import app.croptop.core.data.db.CropTopDatabase
import app.croptop.core.data.db.DocumentPresetDao
import app.croptop.core.data.db.RecentDocumentDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun preferences(impl: DataStorePreferencesRepository): PreferencesRepository

    @Binds
    abstract fun gradients(impl: DataStoreGradientRepository): GradientRepository

    @Binds
    abstract fun recents(impl: RoomRecentDocumentsRepository): RecentDocumentsRepository

    @Binds
    abstract fun uriGrants(impl: ContentResolverUriGrants): UriGrants

    @Binds
    abstract fun catalog(impl: app.croptop.core.data.catalog.RoomCatalogRepository): app.croptop.core.data.catalog.CatalogRepository

    @Binds
    abstract fun folderSource(impl: app.croptop.core.data.catalog.SafFolderSource): app.croptop.core.data.catalog.FolderSource

    @Binds
    abstract fun metadataReader(impl: app.croptop.core.data.catalog.ExifMetadataReader): app.croptop.core.data.catalog.MetadataReader

    @Binds
    abstract fun develop(impl: app.croptop.core.data.RoomDevelopRepository): app.croptop.core.data.DevelopRepository

    @Binds
    abstract fun presets(impl: app.croptop.core.data.RoomDocumentPresetsRepository): app.croptop.core.data.DocumentPresetsRepository

    @Binds
    abstract fun fonts(impl: app.croptop.core.data.DataStoreFontRepository): app.croptop.core.data.FontRepository

    @Binds
    abstract fun googleFonts(impl: app.croptop.core.data.DiskGoogleFonts): app.croptop.core.data.GoogleFonts

    @Binds
    abstract fun fontSource(impl: app.croptop.core.data.SystemFontSource): app.croptop.core.data.FontSource

    @Binds
    abstract fun documentNames(impl: app.croptop.core.data.ContentResolverDocumentNames): app.croptop.core.data.DocumentNames

    @Binds
    abstract fun brushes(impl: app.croptop.core.data.RoomBrushRepository): app.croptop.core.data.BrushRepository

    companion object {
        @Provides
        @Singleton
        fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("croptop_prefs") }

        @Provides
        @Singleton
        @javax.inject.Named(app.croptop.core.data.DEVICE_STORE)
        fun deviceStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(app.croptop.core.data.DEVICE_STORE) }

        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): CropTopDatabase =
            Room.databaseBuilder(context, CropTopDatabase::class.java, "croptop.db").build()

        @Provides
        fun recentDocumentDao(db: CropTopDatabase): RecentDocumentDao = db.recentDocuments()

        @Provides
        fun documentPresetDao(db: CropTopDatabase): DocumentPresetDao = db.documentPresets()

        @Provides
        fun catalogDao(db: CropTopDatabase): app.croptop.core.data.db.CatalogDao = db.catalog()

        @Provides
        fun brushDao(db: CropTopDatabase): app.croptop.core.data.db.BrushDao = db.brushes()

        @Provides
        fun developDao(db: CropTopDatabase): app.croptop.core.data.db.DevelopDao = db.develop()
    }
}
