package com.xlythe.view.camera.pano

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the sweep guide looks like over the viewfinder, at the start of a sweep, partway and at the
 * end, for a panorama and a photosphere.
 *
 * The states are real: a procedural room is painted into a canvas frame by frame, with coverage
 * tracked alongside, exactly as a sweep does, so the map along the top shows what a sweep would
 * have stitched by then.
 *
 * Record with `gradle :camera-view:recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// One SDK and one density, because a screenshot has to render the same pixels every run.
@Config(sdk = [37], qualifiers = "w360dp-h640dp-mdpi")
class PanoramaGuideScreenshotTest {

    @Test
    fun panoramaStart() = capture("PanoramaGuide_panorama_start",
        sweep(photosphere = false, painted = listOf(0f to 0f)), facing = 4f to 0f)

    @Test
    fun panoramaPartway() = capture("PanoramaGuide_panorama_partway",
        sweep(photosphere = false, painted = (0..150 step 15).map { it.toFloat() to 0f }),
        facing = 160f to 2f)

    @Test
    fun panoramaTooFast() = capture("PanoramaGuide_panorama_too_fast",
        sweep(photosphere = false, painted = (0..60 step 15).map { it.toFloat() to 0f })
            .withStatus(GuideState.TOO_FAST),
        facing = 70f to 0f)

    @Test
    fun panoramaComplete() = capture("PanoramaGuide_panorama_complete",
        sweep(photosphere = false, painted = (0..345 step 15).map { it.toFloat() to 0f })
            .withStatus(GuideState.COMPLETE),
        facing = 0f to 0f)

    @Test
    fun panoramaOffLevel() = capture("PanoramaGuide_panorama_off_level",
        sweep(photosphere = false, painted = (0..90 step 15).map { it.toFloat() to 0f }),
        facing = 95f to 9f)

    @Test
    fun panoramaStalled() = capture("PanoramaGuide_panorama_stalled",
        sweep(photosphere = false, painted = (0..150 step 15).map { it.toFloat() to 0f },
            speed = 0f),
        facing = 152f to 0f)

    @Test
    fun settling() = capture("PanoramaGuide_settling",
        GuideState(false, GuideState.SETTLING, 0f, FRAME_W, FRAME_H, 90, false, FOCAL, 0f, 0f,
            FloatArray(0), FloatArray(0), BooleanArray(0), -1, null, 0, 0, 0f, 0f, 0f, 0f),
        facing = 0f to 0f)

    @Test
    fun photosphereStart() = capture("PanoramaGuide_photosphere_start",
        sweep(photosphere = true, painted = listOf(0f to 0f)), facing = 3f to 0f)

    @Test
    fun photosphereHolding() {
        val state = sweep(photosphere = true, painted = listOf(0f to 0f), hold = 0.6f)
        val target = state.targetYaws[state.nextTarget] to state.targetPitches[state.nextTarget]
        val bitmap = shoot("PanoramaGuide_photosphere_holding", state,
            facing = target.first + 1f to target.second)
        val idle = render(sweep(photosphere = true, painted = listOf(0f to 0f)),
            facing = target.first + 1f to target.second)
        // Held, the reticle fills from the top clockwise; the hold has not reached its left.
        val above = SIZE_H / 2 - 20
        assertNotEquals(bitmap.getPixel(SIZE_W / 2 + 6, above), idle.getPixel(SIZE_W / 2 + 6, above))
    }

    @Test
    fun photospherePartway() = capture("PanoramaGuide_photosphere_partway",
        sweep(photosphere = true,
            painted = (0..345 step 30).map { it.toFloat() to 0f }
                + (0..330 step 33).map { it.toFloat() to 33f }),
        facing = 20f to 40f)

    @Test
    fun photosphereSaving() {
        val state = sweep(photosphere = true,
            painted = (0..345 step 30).flatMap { yaw ->
                listOf(-66f, -33f, 0f, 33f, 66f).map { yaw.toFloat() to it }
            } + listOf(0f to 90f, 0f to -90f))
            .withStatus(GuideState.SAVING)
        capture("PanoramaGuide_photosphere_saving", state, facing = 0f to 0f)
    }

    @Test
    fun frontCamera_mirrorsTheMap() {
        val state = sweep(photosphere = false, painted = (0..60 step 15).map { it.toFloat() to 0f },
            front = true)
        // The device faces away from the lens, which looks back at whoever holds it.
        capture("PanoramaGuide_front", state, facing = 65f to 0f)
    }

    @Test
    fun noSweep_drawsNothing() {
        val context = RuntimeEnvironment.getApplication()
        val guide = PanoramaGuideView(context)
        guide.setSession(null)
        val bitmap = Bitmap.createBitmap(SIZE_W, SIZE_H, Bitmap.Config.ARGB_8888)
        layOut(guide).draw(Canvas(bitmap))
        assertEquals(BACKGROUND, bitmap.getPixel(SIZE_W / 2, SIZE_H / 2))
    }

    @Test
    fun beforeTheFirstFrame_drawsNothing() {
        val bitmap = render(null, facing = 0f to 0f)
        assertEquals(BACKGROUND, bitmap.getPixel(SIZE_W / 2, SIZE_H / 2))
    }

    /** Lays the guide out over a dark background, gives it a state, and captures it. */
    private fun capture(name: String, state: GuideState, facing: Pair<Float, Float>) {
        shoot(name, state, facing)
    }

    private fun shoot(name: String, state: GuideState, facing: Pair<Float, Float>): Bitmap {
        val bitmap = render(state, facing)
        bitmap.captureRoboImage("screenshots/$name.png", OPTIONS)
        return bitmap
    }

    private fun render(state: GuideState?, facing: Pair<Float, Float>): Bitmap {
        val context = RuntimeEnvironment.getApplication()
        val guide = PanoramaGuideView(context)
        val rotation = SphereMathTest.matrixOf(SphereMathTest.uprightFacing(facing.first, facing.second))
        guide.setSource(object : GuideState.Source {
            override fun getGuideState(): GuideState? = state

            override fun getDisplayRotation(matrix: FloatArray): Boolean {
                rotation.copyInto(matrix)
                return true
            }
        })
        guide.freezeTime(250)
        val container = layOut(guide)
        // Drawn to a bitmap rather than captured through a window, as the focus reticle is.
        val bitmap = Bitmap.createBitmap(SIZE_W, SIZE_H, Bitmap.Config.ARGB_8888)
        container.draw(Canvas(bitmap))
        return bitmap
    }

    private fun layOut(guide: View): View {
        val context = RuntimeEnvironment.getApplication()
        val container = FrameLayout(context).apply { setBackgroundColor(BACKGROUND) }
        container.addView(guide, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.measure(
            View.MeasureSpec.makeMeasureSpec(SIZE_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SIZE_H, View.MeasureSpec.EXACTLY))
        container.layout(0, 0, SIZE_W, SIZE_H)
        return container
    }

    /**
     * The guide's state after a sweep painted frames at each (yaw, pitch), the way a sweep paints
     * them: into a canvas, with coverage marked and the thumbnail brought up to date.
     */
    private fun sweep(photosphere: Boolean, painted: List<Pair<Float, Float>>,
                      front: Boolean = false, hold: Float = 0f, speed: Float = 20f): GuideState {
        val rotation = if (front) 270 else 90
        val poses = painted.map { (yaw, pitch) ->
            CameraPose(SphereMathTest.matrixOf(SphereMathTest.uprightFacing(yaw, pitch)), rotation,
                front, FRAME_W, FRAME_H, FOCAL)
        }
        // Centred where the sweep started, as a sweep's canvas is.
        val start = poses[0].yaw()
        val canvas = SphereCanvas(1742, start)
        val coverage = if (photosphere) SweepCoverage.forPhotosphere(start, 46.8f)
        else SweepCoverage.forPanorama(start, 0f, 46.8f)
        val thumbnailWidth = if (photosphere) 720 else 1440
        val thumbnail = IntArray(thumbnailWidth * thumbnailWidth / 2)
        var last: CameraPose? = null
        var offset = 0f
        var left = 0f
        var right = 0f
        for (pose in poses) {
            canvas.paint(room(pose), pose)
            coverage.cover(pose)
            canvas.updateThumbnail(thumbnail, thumbnailWidth, thumbnailWidth / 2)
            last = pose
            // How far the frames reach either side of the start, as a sweep follows it.
            val raw = SphereMath.wrap(pose.yaw() - start)
            offset = raw + 360f * Math.round((offset - raw) / 360f)
            left = minOf(left, offset - 23.4f)
            right = maxOf(right, offset + 23.4f)
        }
        val next = coverage.nextTarget(last!!.forward, -1)
        val targets = coverage.targetCount
        return GuideState(photosphere, GuideState.SWEEPING, coverage.progress, FRAME_W, FRAME_H,
            rotation, front, FOCAL, start, 0f,
            FloatArray(targets) { coverage.getTargetYaw(it) },
            FloatArray(targets) { coverage.getTargetPitch(it) },
            BooleanArray(targets) { coverage.isTargetComplete(it) },
            next, thumbnail, thumbnailWidth, painted.size, hold, speed, left, right)
    }

    /**
     * What a frame sees of a made-up room: a pale ceiling, a dark floor, and walls striped in a
     * colour that changes all the way round, so where each frame landed is plain on the map.
     */
    private fun room(pose: CameraPose): ArgbImage {
        val pixels = IntArray(FRAME_W * FRAME_H)
        val direction = FloatArray(3)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                pose.unproject(x + 0.5f, y + 0.5f, direction)
                val yaw = SphereMath.yaw(direction[0], direction[1])
                val pitch = SphereMath.pitch(direction[0], direction[1], direction[2])
                pixels[y * FRAME_W + x] = when {
                    pitch > 35f -> Color.rgb(205, 200, 190)
                    pitch < -30f -> Color.rgb(90, 70, 55)
                    else -> {
                        val hue = (yaw + 180f) % 360f
                        val stripe = if (((yaw + 360f) / 15f).toInt() % 2 == 0) 1f else 0.8f
                        Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f * stripe))
                    }
                }
            }
        }
        return ArgbImage(pixels, FRAME_W, FRAME_H)
    }

    private companion object {
        const val SIZE_W = 360
        const val SIZE_H = 640

        /** A 160 by 120 sensor seeing 60 degrees across, held upright. */
        const val FRAME_W = 160
        const val FRAME_H = 120
        val FOCAL = (80 / Math.tan(Math.toRadians(30.0))).toFloat()

        /** Dark, so the white and amber show up in the image. */
        val BACKGROUND = Color.rgb(32, 32, 32)

        /** The comparator the focus reticle's screenshots use. */
        val OPTIONS = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(
                    maxDistance = 0.12f, hShift = 2, vShift = 1),
                resultValidator = ThresholdValidator(0.002f)))
    }
}
