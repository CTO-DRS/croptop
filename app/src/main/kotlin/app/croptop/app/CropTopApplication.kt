package app.croptop.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import app.croptop.app.export.BatchExportService
import app.croptop.feature.library.batch.BatchExportController
import app.croptop.feature.library.batch.BatchExportState
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

@HiltAndroidApp
class CropTopApplication : Application() {
    @Inject lateinit var batchExport: BatchExportController

    override fun onCreate() {
        super.onCreate()
        // A batch export keeps running (and reports progress) in a foreground service once the user leaves the Library.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            batchExport.state.distinctUntilChangedBy { it is BatchExportState.Running }.collect { state ->
                if (state is BatchExportState.Running) {
                    ContextCompat.startForegroundService(this@CropTopApplication, Intent(this@CropTopApplication, BatchExportService::class.java))
                }
            }
        }
    }
}
