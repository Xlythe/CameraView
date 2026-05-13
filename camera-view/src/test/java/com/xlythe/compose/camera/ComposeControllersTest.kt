package com.xlythe.compose.camera

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.xlythe.view.camera.Barcode
import com.xlythe.view.camera.CameraView
import com.xlythe.view.camera.VideoStream
import com.xlythe.view.camera.VideoView
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 21)
class ComposeControllersTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.application
    }

    @Test
    fun testVideoController() {
        val controllerState = mutableStateOf<VideoController?>(null)
        val videoView = VideoView(context)
        
        val controller = object : VideoController {
            override fun seekToFirstFrame() {
                videoView.seekToFirstFrame()
            }

            override fun play() {
                videoView.play()
            }

            override fun pause() {
                videoView.pause()
            }
        }
        
        controllerState.value = controller
        assertNotNull(controllerState.value)
    }

    @Test
    fun testCameraController() {
        val controllerState = mutableStateOf<CameraController?>(null)
        val cameraView = CameraView(context)

        val controller = object : CameraController {
            override fun takePicture(file: File) {
                cameraView.takePicture(file)
            }

            override fun startRecording(file: File) {
                cameraView.startRecording(file)
            }

            override fun stopRecording() {
                cameraView.stopRecording()
            }

            override fun stream(): VideoStream? {
                return null
            }

            override fun toggleCamera() {
                cameraView.toggleCamera()
            }

            override fun confirmPicture() {
                cameraView.confirmPicture()
            }

            override fun confirmVideo() {
                cameraView.confirmVideo()
            }

            override fun enterBarcodeScanner(listener: (List<Barcode>) -> Unit, vararg formats: Int) {}

            override fun exitBarcodeScanner() {}
        }

        controllerState.value = controller
        assertNotNull(controllerState.value)
    }
}
