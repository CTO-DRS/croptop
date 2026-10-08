package app.croptop.app.di

import android.content.Context
import app.croptop.core.ai.AiEditActions
import app.croptop.core.ai.AiRuntime
import app.croptop.core.ai.HttpDownloader
import app.croptop.core.ai.LiteRtBackend
import app.croptop.core.ai.ModelAiEditActions
import app.croptop.core.ai.ModelManager
import app.croptop.core.ai.ModelRegistry
import app.croptop.core.ai.ModelStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    /** Models live under no-backup storage: they are large, re-downloadable, and must not enter cloud backups. */
    @Provides
    @Singleton
    fun modelManager(@ApplicationContext context: Context): ModelManager {
        val registry = runCatching {
            context.assets.open(MANIFEST).bufferedReader().use { ModelRegistry.parse(it.readText()) }
        }.getOrElse { ModelRegistry.of(emptyList()) } // no manifest shipped yet: the AI models section stays hidden
        val store = ModelStore(File(context.noBackupFilesDir, "models"), HttpDownloader())
        return ModelManager(store, registry, CoroutineScope(SupervisorJob()))
    }

    /** The editor's AI actions: the models on LiteRT (CPU; accelerators join once they are verified on devices) plus model downloads. */
    @Provides
    @Singleton
    fun aiEditActions(manager: ModelManager): AiEditActions = ModelAiEditActions(AiRuntime(manager, LiteRtBackend()), manager)

    private const val MANIFEST = "ai/models.json"
}
