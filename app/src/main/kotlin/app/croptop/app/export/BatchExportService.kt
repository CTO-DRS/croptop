package app.croptop.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.croptop.app.MainActivity
import app.croptop.feature.library.batch.BatchExportController
import app.croptop.feature.library.batch.BatchExportState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a Library batch export alive while the app is in the background and shows its progress and outcome as a notification.
 * It runs only while a batch is running; the queue itself lives in [BatchExportController].
 */
@AndroidEntryPoint
class BatchExportService : Service() {
    @Inject lateinit var controller: BatchExportController

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            controller.cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, progress(0, 1, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher == null) {
            watcher = scope.launch {
                controller.state.collect { state ->
                    val manager = getSystemService(NotificationManager::class.java)
                    when (state) {
                        is BatchExportState.Running -> manager.notify(NOTIFICATION_ID, progress(state.completed, state.total, state.current))

                        is BatchExportState.Finished -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            manager.notify(DONE_ID, finished(state))
                            stopSelf()
                        }

                        BatchExportState.Idle -> stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, BatchExportService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(done: Int, total: Int, current: String?): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Exporting photos")
            .setContentText(current?.let { "${done + 1} of $total · $it" } ?: "Starting…")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            .addAction(Notification.Action.Builder(null, "Cancel", cancelIntent()).build())
            .build()

    private fun finished(state: BatchExportState.Finished): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                when {
                    state.cancelled -> "Export cancelled"
                    state.failures.isEmpty() -> "Export finished"
                    else -> "Export finished with problems"
                },
            )
            .setContentText(
                "${state.succeeded} of ${state.total} exported" + if (state.failures.isNotEmpty()) ", ${state.failures.size} failed" else "",
            )
            .setAutoCancel(true)
            .setContentIntent(open())
            .build()

    companion object {
        const val CHANNEL = "batch-export"
        const val NOTIFICATION_ID = 4101
        const val DONE_ID = 4102
        const val ACTION_CANCEL = "app.croptop.export.CANCEL"
    }
}
