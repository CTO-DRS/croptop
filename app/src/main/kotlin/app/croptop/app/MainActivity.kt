package app.croptop.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import app.croptop.app.navigation.CropTopApp
import app.croptop.app.navigation.PendingOpen
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.ShortcutScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var actions: ActionDispatcher

    /** Documents requested by VIEW/EDIT/SEND intents, consumed by the navigation host. */
    private val pendingOpen = MutableStateFlow<PendingOpen?>(null)

    /** Debug builds log janky frames with the current screen; off in release. */
    private var jankStats: androidx.metrics.performance.JankStats? = null

    override fun onResume() {
        super.onResume()
        jankStats?.isTrackingEnabled = true
    }

    override fun onPause() {
        super.onPause()
        jankStats?.isTrackingEnabled = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Desktop windowing: draw our own header into the caption bar (no duplicate title row).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val transparent = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
            window.insetsController?.setSystemBarsAppearance(transparent, transparent)
        }
        if (savedInstanceState == null) handleIntent(intent)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            jankStats = androidx.metrics.performance.JankStats.createAndTrack(window) { frame ->
                if (frame.isJank) {
                    android.util.Log.d(
                        "CropTopJank",
                        "${frame.frameDurationUiNanos / 1_000_000}ms ${frame.states.joinToString { "${it.key}=${it.value}" }}",
                    )
                }
            }
        }
        setContent {
            CropTopApp(
                pendingOpen = pendingOpen,
                actions = actions,
                onOpenInNewWindow = ::openInNewWindow,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) pendingOpen.value = PendingOpen(uri.toString())
    }

    /** "Open in new window": a new task placed next to this one in desktop windowing. */
    private fun openInNewWindow(uri: String?) {
        val i = Intent(this, MainActivity::class.java).apply {
            action = if (uri != null) Intent.ACTION_VIEW else Intent.ACTION_MAIN
            if (uri != null) data = uri.toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(i)
    }

    /**
     * Shortcuts run before focus dispatch so tool keys work anywhere in the window.
     * Text fields opt out via [ActionDispatcher.textInputActive].
     */
    @SuppressLint("RestrictedApi") // Activity.dispatchKeyEvent is public; lint flags androidx.core's override.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        actions.dispatch(event) || super.dispatchKeyEvent(event)

    /** Publishes the active screen's shortcuts to the system helper (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(actions.shortcuts.keyboardShortcutGroups(setOf(ShortcutScope.GLOBAL, actions.activeScope)))
    }
}
