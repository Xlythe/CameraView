package com.xlythe.view.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the focus reticle looks like in each state it can be drawn in.
 *
 * Record with `gradle :camera-view:recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// One SDK and one density, because a screenshot has to render the same pixels every run.
@Config(sdk = [37], qualifiers = "w360dp-h360dp-mdpi")
class FocusReticleScreenshotTest {

    @Test
    fun focusing() = capture("FocusReticle_focusing") { it.showStatic(AREA, false, 1f) }

    @Test
    fun entering() = capture("FocusReticle_entering") { it.showStatic(AREA, false, 0f) }

    @Test
    fun locked() = capture("FocusReticle_locked") { it.showStatic(AREA, true, 1f) }

    @Test
    fun restyled() = capture("FocusReticle_restyled") {
        it.setColors(Color.CYAN, Color.MAGENTA)
        it.showStatic(AREA, true, 1f)
    }

    /** Lays the reticle out over a dark background, puts it in a state, and captures it. */
    private fun capture(name: String, setUp: (FocusReticle) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        // Dark, so a white or amber reticle shows up in the image.
        val container = FrameLayout(context).apply { setBackgroundColor(Color.rgb(32, 32, 32)) }
        val reticle = FocusReticle(context)
        container.addView(reticle, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val exactly = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
        container.measure(exactly, exactly)
        container.layout(0, 0, SIZE, SIZE)
        setUp(reticle)

        // Drawn to a bitmap rather than captured through a window: the reticle is only drawing,
        // and a window would bring in Espresso, whose input injection predates API 37.
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        container.draw(Canvas(bitmap))
        bitmap.captureRoboImage("screenshots/$name.png", OPTIONS)
    }

    private companion object {
        const val SIZE = 360

        /** A metering area in the middle of the view, the size a tap usually produces. */
        val AREA = RectF(130f, 130f, 230f, 230f)

        /** The comparator the other Xlythe screenshot suites use, so a baseline recorded on one OS
         * verifies on another: antialiasing moves edge pixels by a pixel or two. */
        val OPTIONS = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(
                        imageComparator = SimpleImageComparator(
                                maxDistance = 0.12f, hShift = 2, vShift = 1),
                        resultValidator = ThresholdValidator(0.002f)))
    }
}
