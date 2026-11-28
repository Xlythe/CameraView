package com.xlythe.compose.camera

import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.xlythe.view.camera.viewer.LargeImageView
import com.xlythe.view.camera.viewer.MediaView
import com.xlythe.view.camera.viewer.PanoramaView
import com.xlythe.view.camera.viewer.PhotosphereView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSensor
import java.io.File

/**
 * The Compose wrappers around the viewers: each makes its view, hands it the file, and reports
 * back through the lambdas the latest composition passed.
 */
@RunWith(RobolectricTestRunner::class)
// The region decoder Robolectric stands in for is the one before API 31.
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ViewersComposableTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var picture: File

    /** A size of its own, since the viewers take none from what they show. */
    private val SQUARE = Modifier.layout { measurable, _ ->
        val placeable = measurable.measure(Constraints.fixed(200, 200))
        layout(200, 200) { placeable.place(0, 0) }
    }

    @Before
    fun setUp() {
        picture = File.createTempFile("viewer", ".jpg")
        picture.deleteOnExit()
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        picture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test
    fun aLargeImageShowsItsFile() {
        val loaded = mutableListOf<String>()
        val which = mutableStateOf("first")
        val file = mutableStateOf<File?>(null)
        compose.setContent {
            val label = which.value
            LargeImage(
                modifier = SQUARE,
                file = file.value,
                onLoaded = { loaded.add(label) },
            )
        }
        compose.waitForIdle()
        // The view was made with the first lambda, and hears with whichever is latest.
        which.value = "second"
        compose.waitForIdle()
        file.value = picture
        compose.waitForIdle()

        awaitTrue { loaded.isNotEmpty() }
        assertEquals("the latest lambda hears it", listOf("second"), loaded)
        assertTrue(find(LargeImageView::class.java) !is PanoramaView)
    }

    @Test
    fun aLargeImageReportsAFailure() {
        var failed = 0
        compose.setContent {
            LargeImage(
                modifier = SQUARE,
                file = File(picture.parentFile, "missing.jpg"),
                onFailed = { failed++ },
            )
        }
        awaitTrue { failed > 0 }
        assertEquals(1, failed)
    }

    @Test
    fun aPanoramaShowsItsUri() {
        var loaded = false
        compose.setContent {
            Panorama(
                modifier = SQUARE,
                uri = Uri.fromFile(picture),
                onLoaded = { loaded = true },
            )
        }
        awaitTrue { loaded }
        assertNotNull(find(PanoramaView::class.java))
    }

    /** The gyroscope follows the parameter, on and off again. */
    @Test
    fun aPhotosphereFollowsTheGyroscopeParameter() {
        val sensors = compose.activity.getSystemService(SensorManager::class.java)
        Shadows.shadowOf(sensors)
            .addSensor(ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR))
        val gyroscope = mutableStateOf(true)
        compose.setContent {
            Photosphere(
                modifier = SQUARE,
                file = picture,
                gyroscope = gyroscope.value,
            )
        }
        compose.waitForIdle()
        val view = find(PhotosphereView::class.java)
        assertTrue(view.isGyroscopeEnabled)

        gyroscope.value = false
        compose.waitForIdle()
        assertFalse(view.isGyroscopeEnabled)
        assertTrue("the same view, not a new one", view === find(PhotosphereView::class.java))
    }

    /** Media works out what the file is, and shows it as another type when asked. */
    @Test
    fun mediaShowsAPictureAsTheTypeAskedFor() {
        val loaded = mutableListOf<Int>()
        val type = mutableStateOf(MediaView.UNKNOWN)
        compose.setContent {
            Media(
                modifier = SQUARE,
                file = picture,
                type = type.value,
                onLoaded = { loaded.add(it) },
            )
        }
        awaitTrue { loaded.isNotEmpty() }
        assertEquals(listOf(MediaView.PICTURE), loaded)
        val view = find(MediaView::class.java)
        assertEquals(MediaView.PICTURE, view.mediaType)
        assertTrue(find(view, LargeImageView::class.java) !is PanoramaView)

        type.value = MediaView.PANORAMA
        // The new viewer decodes once Compose has laid it out.
        compose.waitForIdle()
        awaitTrue { loaded.size > 1 }
        assertEquals(MediaView.PANORAMA, loaded.last())
        assertNotNull(find(view, PanoramaView::class.java))
        assertTrue("the same view, not a new one", view === find(MediaView::class.java))
    }

    /** Each affordance follows its parameter. */
    @Test
    fun mediaFollowsItsParameters() {
        val on = mutableStateOf(false)
        compose.setContent {
            Media(
                modifier = SQUARE,
                file = picture,
                tapToPlay = on.value,
                holdToPlay = on.value,
                showProgress = on.value,
                hdr = on.value,
                showError = on.value,
            )
        }
        compose.waitForIdle()
        val view = find(MediaView::class.java)
        assertFalse(view.isTapToPlayEnabled)
        assertFalse(view.isHoldToPlayEnabled)
        assertFalse(view.isProgressVisible)
        assertFalse(view.isHdrEnabled)
        assertNull(view.errorPlaceholder)

        on.value = true
        compose.waitForIdle()
        assertTrue(view.isTapToPlayEnabled)
        assertTrue(view.isHoldToPlayEnabled)
        assertTrue(view.isProgressVisible)
        assertTrue(view.isHdrEnabled)
        // The library's own icon comes back.
        assertNotNull(view.errorPlaceholder)
    }

    @Test
    fun mediaReportsAFailure() {
        var failed = 0
        compose.setContent {
            Media(
                modifier = SQUARE,
                uri = Uri.fromFile(File(picture.parentFile, "missing.mp4")),
                onFailed = { failed++ },
            )
        }
        awaitTrue { failed > 0 }
        assertEquals(1, failed)
    }

    /** Runs the main thread, while the viewer decodes on its own, until something is true. */
    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun <T : View> find(type: Class<T>): T {
        val found = find(compose.activity.window.decorView, type)
        assertNotNull("the composable should have added a ${type.simpleName}", found)
        return found!!
    }

    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) {
            return type.cast(view)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = find(view.getChildAt(index), type)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }
}
