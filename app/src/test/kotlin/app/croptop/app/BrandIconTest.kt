package app.croptop.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: these tests only draw resources, so the app's own start-up (native engine) is not needed.
@Config(sdk = [35], application = android.app.Application::class)
class BrandIconTest {
    @Test
    fun launcherArtworkFitsInsideCenteredCircle() {
        val bitmap = render(R.drawable.ic_launcher_foreground, 108, 108)
        var visible = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    visible++
                    val dx = x + 0.5 - 54
                    val dy = y + 0.5 - 54
                    assertTrue("Artwork at $x,$y needs more launcher padding", dx * dx + dy * dy <= 33 * 33)
                }
            }
        }
        assertTrue("Launcher artwork must remain visible", visible > 500)
    }

    @Test
    fun themedIconUsesSameSilhouetteAndTransparentBackground() {
        val color = render(R.drawable.ic_launcher_foreground, 108, 108)
        val monochrome = render(R.drawable.ic_launcher_monochrome, 108, 108)
        for (y in 0 until 108) {
            for (x in 0 until 108) {
                assertTrue(Color.alpha(color.getPixel(x, y)) == Color.alpha(monochrome.getPixel(x, y)))
            }
        }
    }

    @Test
    fun inAppMarkHasClearEdges() {
        val mark = render(app.croptop.core.designsystem.R.drawable.croptop_mark, 74, 100)
        var visible = 0
        for (y in 0 until mark.height) {
            for (x in 0 until mark.width) {
                if (Color.alpha(mark.getPixel(x, y)) > 0) {
                    visible++
                    assertTrue("In-app artwork touches its viewport edge", x >= 3 && x < 71 && y >= 4 && y < 96)
                }
            }
        }
        assertTrue(visible > 500)
    }

    private fun render(resource: Int, width: Int, height: Int): Bitmap {
        val drawable = requireNotNull(ContextCompat.getDrawable(ApplicationProvider.getApplicationContext(), resource))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
