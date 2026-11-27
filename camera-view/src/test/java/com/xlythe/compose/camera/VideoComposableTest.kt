package com.xlythe.compose.camera

import android.Manifest
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.xlythe.view.camera.VideoView
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File

/**
 * The Compose wrapper around VideoView.
 *
 * <p>Same shape as the camera wrapper and the same reasons to test it: the view is built in a
 * `remember`, the controller is an object closed over it, and the attributes are pushed in an
 * update block, so none of it can be reached without composing. It was entirely uncovered.</p>
 */
@RunWith(RobolectricTestRunner::class)
// API 34: Compose's own view calls an InputManager method the platform removed afterwards.
@Config(sdk = [34], shadows = [ShadowAvailableTextureView::class])
class VideoComposableTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var file: File

    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        file = File.createTempFile("compose", ".mp4")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0, 0, 0, 1))

        // The view opens a video through a file descriptor, which Robolectric's fakes key on; one
        // name for all of them is what makes the prepare path reachable.
        DataSource.setFileDescriptorTransform { _, _ -> VIDEO }
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(5_000, 0) }
        ShadowMediaPlayer.setCreateListener { _, shadow ->
            shadow.doSetDataSource(DataSource.toDataSource(VIDEO))
        }
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(VIDEO),
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "640"
        )
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(VIDEO),
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "480"
        )
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(VIDEO),
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "0"
        )
    }

    @After
    fun tearDown() {
        DataSource.reset()
        ShadowMediaMetadataRetriever.reset()
    }

    @Test
    fun theControllerIsPublishedToTheCaller() {
        val controller = mutableStateOf<VideoController?>(null)

        compose.setContent { Video(controller = controller) }
        compose.waitForIdle()

        assertNotNull("the caller cannot drive playback without it", controller.value)
    }

    /** Every attribute the composable takes has to land on the view it is wrapping. */
    @Test
    fun theAttributesReachTheView() {
        val controller = mutableStateOf<VideoController?>(null)

        compose.setContent {
            Video(
                mirror = true,
                loop = true,
                volume = 0.5f,
                file = file,
                controller = controller,
            )
        }
        compose.waitForIdle()

        val video = requireVideoView()
        assertTrue(video.isMirrored)
        assertTrue(video.isLooping)
        assertEquals(file, video.file)
    }

    /** A changed attribute is pushed again, not only the first composition's value. */
    @Test
    fun aChangedAttributeReachesTheView() {
        val mirror = mutableStateOf(false)
        val controller = mutableStateOf<VideoController?>(null)

        compose.setContent { Video(mirror = mirror.value, controller = controller) }
        compose.waitForIdle()
        assertFalse(requireVideoView().isMirrored)

        mirror.value = true
        compose.waitForIdle()

        assertTrue(requireVideoView().isMirrored)
    }

    /** The same view survives a recomposition, rather than the video restarting. */
    @Test
    fun recomposingKeepsTheSameView() {
        val loop = mutableStateOf(false)
        val controller = mutableStateOf<VideoController?>(null)
        compose.setContent { Video(loop = loop.value, controller = controller) }
        compose.waitForIdle()
        val first = requireVideoView()

        loop.value = true
        compose.waitForIdle()

        assertTrue(first === requireVideoView())
    }

    /** The controller drives playback, and is safe to call with nothing to play. */
    @Test
    fun theControllerDrivesPlayback() {
        val controller = mutableStateOf<VideoController?>(null)
        compose.setContent { Video(file = file, controller = controller) }
        compose.waitForIdle()
        val video = requireVideoView()

        requireNotNull(controller.value).play()
        compose.waitForIdle()
        assertTrue(video.isPlaying)

        requireNotNull(controller.value).pause()
        compose.waitForIdle()
        assertFalse(video.isPlaying)

        requireNotNull(controller.value).seekToFirstFrame()
        compose.waitForIdle()
        assertFalse("seeking is not playing", video.isPlaying)
    }

    /** With nothing to play, the controller's calls are refused rather than crashing. */
    @Test
    fun theControllerWithNothingToPlayIsHarmless() {
        val controller = mutableStateOf<VideoController?>(null)
        compose.setContent { Video(controller = controller) }
        compose.waitForIdle()

        val video = requireNotNull(controller.value)
        video.play()
        video.pause()
        video.seekToFirstFrame()
        compose.waitForIdle()

        assertFalse(requireVideoView().isPlaying)
    }

    /** Completion is reported to the callback the latest composition passed. */
    @Test
    fun completionIsReportedToTheLatestCallback() {
        val completions = mutableListOf<String>()
        val which = mutableStateOf("first")
        val controller = mutableStateOf<VideoController?>(null)

        compose.setContent {
            val label = which.value
            Video(file = file, controller = controller, onCompletion = { completions.add(label) })
        }
        compose.waitForIdle()
        which.value = "second"
        compose.waitForIdle()

        val player = requireNotNull(requireVideoView().mediaPlayer)
        Shadows.shadowOf(player).invokeCompletionListener()
        compose.waitForIdle()

        assertEquals(listOf("second"), completions)
    }

    /** A uri, such as the one Camera's onVideoSaved reports, is played the way a file is. */
    @Test
    fun aUriReachesThePlayer() {
        val uri = Uri.parse("content://media/external/video/media/1")
        val controller = mutableStateOf<VideoController?>(null)
        compose.setContent { Video(uri = uri, controller = controller) }
        compose.waitForIdle()

        val expected = DataSource.toDataSource(RuntimeEnvironment.getApplication(), uri)
        val deadline = System.currentTimeMillis() + 5_000
        while (Shadows.shadowOf(requireVideoView().mediaPlayer).dataSource != expected
            && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }

        assertEquals(expected, Shadows.shadowOf(requireVideoView().mediaPlayer).dataSource)
        assertEquals("a uri replaces any file", null, requireVideoView().file)
    }

    private fun requireVideoView(): VideoView {
        val found = findVideoView(compose.activity.window.decorView)
        assertNotNull("the composable should have added a VideoView", found)
        return found!!
    }

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findVideoView(view.getChildAt(index))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private companion object {
        /** The one name every file descriptor is mapped onto, so the fakes can be told about it. */
        const val VIDEO = "video"
    }
}
