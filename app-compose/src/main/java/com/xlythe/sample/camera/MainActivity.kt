package com.xlythe.sample.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlythe.view.camera.Barcode
import com.xlythe.compose.camera.Camera
import com.xlythe.compose.camera.CameraController
import com.xlythe.compose.camera.Permissions
import com.xlythe.compose.camera.Video
import com.xlythe.compose.camera.VideoController
import com.xlythe.sample.camera.ui.theme.CameraViewTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DemoScreen(val title: String) {
    SIMPLE("Simple Demo"),
    STREAM("Stream Demo"),
    RESIZING("Resizing Demo"),
    QR_CODE("QR Code Demo")
}

/**
 * MainActivity showcases a multi-screen Jetpack Compose application mirroring the Java sample.
 * It features premium dark aesthetics and demonstrates all library capabilities: basic capture, live streaming, dynamic resizing, and barcode scanning.
 */
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraViewTheme(darkTheme = true) {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var currentScreen by remember { mutableStateOf(DemoScreen.SIMPLE) }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = Color(0xFF1E1E1E),
                            drawerContentColor = Color.White
                        ) {
                            Spacer(Modifier.height(32.dp))
                            Text(
                                text = "Camera Demo",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(Modifier.height(16.dp))

                            DemoScreen.entries.forEach { screen ->
                                NavigationDrawerItem(
                                    label = { Text(screen.title, style = MaterialTheme.typography.bodyLarge) },
                                    selected = currentScreen == screen,
                                    onClick = {
                                        currentScreen = screen
                                        scope.launch { drawerState.close() }
                                    },
                                    icon = {
                                        when (screen) {
                                            DemoScreen.SIMPLE -> Icon(Icons.Default.CameraAlt, contentDescription = null)
                                            DemoScreen.STREAM -> Icon(Icons.Default.Videocam, contentDescription = null)
                                            DemoScreen.RESIZING -> Icon(Icons.Default.Transform, contentDescription = null)
                                            DemoScreen.QR_CODE -> Icon(Icons.Default.QrCode, contentDescription = null)
                                        }
                                    },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                    colors = NavigationDrawerItemDefaults.colors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = Color.White,
                                        selectedIconColor = Color.White,
                                        unselectedTextColor = Color(0xFFB0B0B0),
                                        unselectedIconColor = Color(0xFFB0B0B0)
                                    )
                                )
                            }
                        }
                    }
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color(0xFF121212),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { 
                                    Text(
                                        text = currentScreen.title,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White)
                                    ) 
                                },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = Color(0xFF1E1E1E)
                                )
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (currentScreen) {
                                DemoScreen.SIMPLE -> SimpleDemoScreen()
                                DemoScreen.STREAM -> StreamDemoScreen()
                                DemoScreen.RESIZING -> ResizingDemoScreen()
                                DemoScreen.QR_CODE -> QrCodeDemoScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Throws(IOException::class)
private fun createImageFile(context: Context): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    if (storageDir == null || (!storageDir.exists() && !storageDir.mkdirs())) {
        throw IOException("Cannot create directory for image file.")
    }
    return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
}

@Composable
fun SimpleDemoScreen() {
    val context = LocalContext.current
    val cameraController = remember { mutableStateOf<CameraController?>(null) }
    var hasRequiredPermissions by remember { mutableStateOf(false) }
    var uiStatusText by remember { mutableStateOf("Camera Ready") }

    val allPermissions = remember { (Permissions.REQUIRED_PERMISSIONS + Permissions.OPTIONAL_PERMISSIONS).distinct().toTypedArray() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
        hasRequiredPermissions = Permissions.REQUIRED_PERMISSIONS.all { permissionsMap[it] == true }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(allPermissions) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = uiStatusText,
                modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, color = Color.White)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (hasRequiredPermissions) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Camera(
                    modifier = Modifier.fillMaxSize(),
                    controller = cameraController,
                    onImageConfirmation = { uiStatusText = "Confirm Image?" },
                    onImageCaptured = { savedFile ->
                        uiStatusText = "Saved: ${savedFile.name}"
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    },
                    onImageCaptureFailed = { uiStatusText = "Capture Failed" }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { cameraController.value?.toggleCamera() },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                ) {
                    Text("Toggle Camera")
                }

                Button(
                    onClick = {
                        try {
                            val file = createImageFile(context)
                            uiStatusText = "Capturing..."
                            cameraController.value?.takePicture(file)
                        } catch (e: Exception) {
                            uiStatusText = "Error creating file"
                        }
                    },
                    enabled = cameraController.value != null,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Take Picture")
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Button(onClick = { permissionLauncher.launch(allPermissions) }) {
                    Text("Request Permission")
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun StreamDemoScreen() {
    val context = LocalContext.current
    val cameraController = remember { mutableStateOf<CameraController?>(null) }
    val videoController = remember { mutableStateOf<VideoController?>(null) }
    var videoStream by remember { mutableStateOf<com.xlythe.view.camera.VideoStream?>(null) }
    var hasRequiredPermissions by remember { mutableStateOf(false) }

    val allPermissions = remember { (Permissions.REQUIRED_PERMISSIONS + Permissions.OPTIONAL_PERMISSIONS).distinct().toTypedArray() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
        hasRequiredPermissions = Permissions.REQUIRED_PERMISSIONS.all { permissionsMap[it] == true }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(allPermissions) }

    LaunchedEffect(videoController.value, videoStream) {
        if (videoStream != null && videoController.value != null) {
            videoController.value?.play()
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (hasRequiredPermissions) {
            Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) {
                Camera(
                    modifier = Modifier.fillMaxSize(),
                    controller = cameraController,
                    onCameraOpened = {
                        videoStream = cameraController.value?.stream()
                    }
                )
            }

            // PIP Secondary Live Stream
            Card(
                modifier = Modifier
                    .size(width = 180.dp, height = 120.dp)
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Video(
                    modifier = Modifier.fillMaxSize(),
                    stream = videoStream,
                    controller = videoController
                )
            }
        } else {
            Button(
                onClick = { permissionLauncher.launch(allPermissions) },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Request Permission")
            }
        }
    }
}

@Composable
fun ResizingDemoScreen() {
    val cameraController = remember { mutableStateOf<CameraController?>(null) }
    var isResized by remember { mutableStateOf(false) }
    var hasRequiredPermissions by remember { mutableStateOf(false) }

    val allPermissions = remember { (Permissions.REQUIRED_PERMISSIONS + Permissions.OPTIONAL_PERMISSIONS).distinct().toTypedArray() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
        hasRequiredPermissions = Permissions.REQUIRED_PERMISSIONS.all { permissionsMap[it] == true }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(allPermissions) }

    val boxWidth by animateDpAsState(targetValue = if (isResized) 200.dp else 360.dp, animationSpec = tween(400), label = "width")
    val boxHeight by animateDpAsState(targetValue = if (isResized) 150.dp else 500.dp, animationSpec = tween(400), label = "height")

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (hasRequiredPermissions) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(width = boxWidth, height = boxHeight).clip(RoundedCornerShape(24.dp))) {
                    Camera(
                        modifier = Modifier.fillMaxSize(),
                        controller = cameraController
                    )
                }
            }

            Button(
                onClick = { isResized = !isResized },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
            ) {
                Text("Resize")
            }
        } else {
            Button(
                onClick = { permissionLauncher.launch(allPermissions) },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Request Permission")
            }
        }
    }
}

@Composable
fun QrCodeDemoScreen() {
    val context = LocalContext.current
    val cameraController = remember { mutableStateOf<CameraController?>(null) }
    var detectedBarcodes by remember { mutableStateOf<List<Barcode>>(emptyList()) }
    var hasRequiredPermissions by remember { mutableStateOf(false) }

    val allPermissions = remember { (Permissions.REQUIRED_PERMISSIONS + Permissions.OPTIONAL_PERMISSIONS).distinct().toTypedArray() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
        hasRequiredPermissions = Permissions.REQUIRED_PERMISSIONS.all { permissionsMap[it] == true }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(allPermissions) }

    LaunchedEffect(cameraController.value) {
        cameraController.value?.enterBarcodeScanner(
            listener = { barcodes -> detectedBarcodes = barcodes },
            com.xlythe.view.camera.Barcode.Format.ALL_FORMATS
        )
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.value?.exitBarcodeScanner() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Barcodes detected: ${detectedBarcodes.size}",
                modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, color = Color.White)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (hasRequiredPermissions) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))) {
                Camera(
                    modifier = Modifier.fillMaxSize(),
                    controller = cameraController
                )

                if (detectedBarcodes.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        detectedBarcodes.forEach { barcode ->
                            val url = barcode.url
                            val wifi = barcode.wifi
                            val phone = barcode.phone
                            val email = barcode.email
                            val sms = barcode.sms
                            val geo = barcode.geoPoint
                            val text = barcode.displayValue ?: barcode.rawValue

                            val (title, action) = when {
                                url != null && url.url != null -> "Open URL: ${url.url}" to {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.url))) } catch (_: Exception) {}
                                }
                                wifi != null && wifi.ssid != null -> "Connect Wi-Fi: ${wifi.ssid}" to {
                                    Toast.makeText(context, "Connecting to Wi-Fi: ${wifi.ssid}", Toast.LENGTH_SHORT).show()
                                }
                                phone != null && phone.number != null -> "Call: ${phone.number}" to {
                                    try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.number}"))) } catch (_: Exception) {}
                                }
                                email != null && email.address != null -> "Email: ${email.address}" to {
                                    try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${email.address}"))) } catch (_: Exception) {}
                                }
                                sms != null && sms.phoneNumber != null -> "SMS: ${sms.phoneNumber}" to {
                                    try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${sms.phoneNumber}"))) } catch (_: Exception) {}
                                }
                                geo != null -> "Location: ${geo.lat}, ${geo.lng}" to {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${geo.lat},${geo.lng}"))) } catch (_: Exception) {}
                                }
                                text != null -> "Search: $text" to {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}"))) } catch (_: Exception) {}
                                }
                                else -> "Barcode detected" to {}
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { action() },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(6.dp)
                            ) {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Button(onClick = { permissionLauncher.launch(allPermissions) }) {
                Text("Request Permission")
            }
        }
    }
}
