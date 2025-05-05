package com.xlythe.sample.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.xlythe.compose.camera.Camera
import com.xlythe.compose.camera.CameraController
import com.xlythe.sample.camera.ui.theme.CameraViewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraViewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val cameraController = remember { mutableStateOf<CameraController?>(null) }
                    Camera(
                        modifier = Modifier.padding(innerPadding),
                        controller = cameraController,
                    )
                }
            }
        }
    }
}
