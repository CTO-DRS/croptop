package app.croptop.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile generation: `./gradlew :app:generateFullReleaseBaselineProfile` with the
 * Desktop AVD or a device attached. Startup goes into the startup profile; the rest covers the editor,
 * library and settings so their first frames are AOT-compiled.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        launchToHome()
    }

    @Test
    fun journeys() = rule.collect(packageName = PACKAGE) {
        launchToHome()
        editorJourney()
        libraryJourney()
        settingsJourney()
    }
}
