package app.croptop.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until

const val PACKAGE = "app.croptop"
private const val TIMEOUT = 5_000L

private fun MacrobenchmarkScope.tap(selector: BySelector, timeout: Long = TIMEOUT): Boolean {
    val found = device.wait(Until.findObject(selector), timeout) ?: return false
    found.click()
    device.waitForIdle()
    return true
}

private fun MacrobenchmarkScope.key(code: Int, meta: Int = 0) {
    device.pressKeyCode(code, meta)
    device.waitForIdle()
}

/** First launch shows the welcome tour; later launches go straight to Home. */
fun MacrobenchmarkScope.launchToHome() {
    pressHome()
    startActivityAndWait()
    tap(By.text("Skip"), timeout = 2_000)
    device.wait(Until.hasObject(By.text("New canvas…")), TIMEOUT)
}

/** Home → New canvas → editor: menus, pan, new layer, undo/redo, zoom; then close without saving. */
fun MacrobenchmarkScope.editorJourney() {
    if (!tap(By.text("New canvas…"))) return
    tap(By.text("Create"))
    device.wait(Until.hasObject(By.text("Layer")), TIMEOUT)
    listOf("File", "Layer", "View").forEach { menu ->
        if (tap(By.text(menu))) key(KeyEvent.KEYCODE_ESCAPE)
    }
    val w = device.displayWidth
    val h = device.displayHeight
    // The default Hand tool pans with a single pointer.
    repeat(3) { device.swipe(w / 2, h / 2, w / 2 - 200, h / 2 - 120, 20) }
    val ctrl = KeyEvent.META_CTRL_ON
    val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
    repeat(3) { key(KeyEvent.KEYCODE_N, ctrlShift) } // New Layer
    key(KeyEvent.KEYCODE_Z, ctrl)
    key(KeyEvent.KEYCODE_Z, ctrlShift)
    key(KeyEvent.KEYCODE_EQUALS, ctrl)
    key(KeyEvent.KEYCODE_0, ctrl)
    key(KeyEvent.KEYCODE_K, ctrl) // command palette
    key(KeyEvent.KEYCODE_ESCAPE)
    key(KeyEvent.KEYCODE_W, ctrl) // close → unsaved changes
    tap(By.text("Don't Save"))
}

fun MacrobenchmarkScope.libraryJourney() {
    if (!tap(By.text("Library"))) return
    device.wait(Until.hasObject(By.text("All Photos")), TIMEOUT)
    device.pressBack()
    device.waitForIdle()
}

fun MacrobenchmarkScope.settingsJourney() {
    if (!tap(By.desc("Settings"))) return
    device.wait(Until.hasObject(By.text("Appearance".uppercase())), TIMEOUT)
    val w = device.displayWidth
    val h = device.displayHeight
    device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 30)
    device.pressBack()
    device.waitForIdle()
}
