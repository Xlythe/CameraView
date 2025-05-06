package com.xlythe.sample.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xlythe.compose.camera.Camera
import com.xlythe.compose.camera.CameraController
import com.xlythe.compose.camera.Permissions
import com.xlythe.sample.camera.ui.theme.CameraViewTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraViewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val context = LocalContext.current
                    val cameraController = remember { mutableStateOf<CameraController?>(null) }
                    var hasRequiredPermissions by remember { mutableStateOf(false) }
                    var uiStatusText by remember { mutableStateOf("Request Permission") }

                    // --- Permission Handling ---
                    val allPermissionsToRequest = remember {
                        (Permissions.REQUIRED_PERMISSIONS + Permissions.OPTIONAL_PERMISSIONS).distinct().toTypedArray()
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions(),
                        onResult = { permissionsMap ->
                            hasRequiredPermissions = Permissions.REQUIRED_PERMISSIONS.all { permissionsMap[it] == true }
                            uiStatusText = if (hasRequiredPermissions) "Required Permissions Granted" else "Required Permissions Needed"
                            Log.d("MainActivity", "Required permissions granted: $hasRequiredPermissions")

                            // --- Log status of ALL requested permissions ---
                            Log.d("MainActivity", "Full permission status:")
                            permissionsMap.forEach { (perm, granted) ->
                                Log.d("MainActivity", "  $perm: ${if (granted) "Granted" else "Denied"}")
                            }
                        }
                    )

                    // Request permission when the composable first enters composition
                    LaunchedEffect(Unit) {
                        Log.d("MainActivity", "Requesting permissions...")
                        permissionLauncher.launch(allPermissionsToRequest)
                    }

                    // --- File Creation Logic ---
                    @Throws(IOException::class)
                    fun createImageFile(context: Context): File {
                        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val imageFileName = "JPEG_${timeStamp}_"
                        val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                        if (storageDir == null || (!storageDir.exists() && !storageDir.mkdirs())) {
                            throw IOException("Cannot create directory for image file.")
                        }
                        return File.createTempFile(imageFileName, ".jpg", storageDir).also {
                            Log.d("MainActivity", "Created file: ${it.absolutePath}")
                        }
                    }

                    // --- UI Structure ---
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = uiStatusText, modifier = Modifier.padding(8.dp))

                        // --- Conditional Content based on Permission ---
                        if (hasRequiredPermissions) {
                            // Camera Composable fills available space (weight 1f)
                            Camera(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                controller = cameraController,
                                onImageConfirmation = {
                                    Log.i("MainActivity", "Image confirmation requested.")
                                    uiStatusText = "Confirm Image?"
                                },
                                onImageCaptured = { savedFile ->
                                    Log.i("MainActivity", "Image captured: ${savedFile.absolutePath}")
                                    uiStatusText = "Image Saved: ${savedFile.name}"
                                    Toast.makeText(context, "Image Saved: ${savedFile.name}", Toast.LENGTH_SHORT).show()
                                },
                                onImageCaptureFailed = {
                                    Log.e("MainActivity", "Image capture failed.")
                                    uiStatusText = "Image Capture Failed"
                                    Toast.makeText(context, "Image Capture Failed", Toast.LENGTH_SHORT).show()
                                },
                            )

                            // Spacer between Camera and Button
                            Spacer(modifier = Modifier.height(16.dp))

                            // Take Picture Button - Placed below the camera view
                            Button(
                                onClick = {
                                    try {
                                        val photoFile = createImageFile(context)
                                        uiStatusText = "Taking picture..."
                                        // Call takePicture via the controller
                                        cameraController.value?.takePicture(photoFile)
                                            ?: run { // Handle null controller case
                                                Log.e("MainActivity", "CameraController is null, cannot take picture.")
                                                uiStatusText = "Error: Camera not ready"
                                            }
                                    } catch (ex: IOException) {
                                        Log.e("MainActivity", "Error creating image file", ex)
                                        uiStatusText = "Error creating file"
                                    }
                                },
                                // Enable button only if the controller is available
                                enabled = cameraController.value != null
                            ) {
                                Text("Take Picture")
                            }

                            // Add Spacer at the bottom if needed
                            Spacer(modifier = Modifier.height(16.dp))

                        } else {
                            // Show permission request button if permission not granted
                            Spacer(modifier = Modifier.weight(1f)) // Push button down
                            Button(onClick = {
                                // Re-launch permission request
                                permissionLauncher.launch(allPermissionsToRequest)
                            }) {
                                Text("Request Camera Permission")
                            }
                            Spacer(modifier = Modifier.weight(1f)) // Push button up
                        }
                    }
                }
            }
        }
    }
}
