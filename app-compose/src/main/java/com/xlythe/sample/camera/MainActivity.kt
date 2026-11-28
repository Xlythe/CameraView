package com.xlythe.sample.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlythe.view.camera.Barcode
import com.xlythe.compose.camera.Camera
import com.xlythe.compose.camera.CameraController
import com.xlythe.compose.camera.CameraState
import com.xlythe.compose.camera.PanoramaMode
import com.xlythe.compose.camera.PictureFormat
import com.xlythe.compose.camera.Permissions
import com.xlythe.compose.camera.Video
import com.xlythe.compose.camera.VideoController
import com.xlythe.sample.camera.ui.theme.CameraViewTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import com.xlythe.compose.camera.Media
import com.xlythe.view.camera.MediaTags
import com.xlythe.view.camera.viewer.MediaView
import java.io.File

/** Where a held burst stops on its own, so a button held by accident does not fill the disk. */
private const val MAX_BURST_PICTURES = 50

enum class DemoScreen(val title: String) {
    SIMPLE("Simple Demo"),
    STREAM("Stream Demo"),
    RESIZING("Resizing Demo"),
    PANORAMA("Panorama Demo"),
    QR_CODE("QR Code Demo"),
    VIEWER("Media Demo"),
    EDITOR("Editor Demo")
}

/** What the media demo can show a file as: whatever it is found to be, or one kind. */
enum class ViewerMode(val label: String, val type: Int) {
    /** Whatever it is found to be. */
    AUTO("Auto", MediaView.UNKNOWN),
    /** Flat, to zoom into. */
    PICTURE("Picture", MediaView.PICTURE),
    /** As a strip to pan along. */
    PANORAMA("Panorama", MediaView.PANORAMA),
    /** From the inside of a sphere. */
    PHOTOSPHERE("Sphere", MediaView.PHOTOSPHERE),
    /** As a video. */
    VIDEO("Video", MediaView.VIDEO),
    /** As a motion photo, whose clip plays while held. */
    MOTION("Motion", MediaView.MOTION_PHOTO),
    /** As a 360 degree video. */
    SPHERICAL("360°", MediaView.SPHERICAL_VIDEO),
    /** As a recording. */
    AUDIO("Sound", MediaView.AUDIO),
    /** As an animated picture. */
    ANIMATION("Animation", MediaView.ANIMATION);

    companion object {
        /** The choice that names a media type, or AUTO for one no choice names. */
        fun of(type: Int): ViewerMode = entries.firstOrNull { it.type == type } ?: AUTO
    }
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
                // The picture the viewer opens on, from the simple demo's last capture.
                var viewerUri by remember { mutableStateOf<Uri?>(null) }

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
                                            DemoScreen.PANORAMA -> Icon(Icons.Default.Panorama, contentDescription = null)
                                            DemoScreen.QR_CODE -> Icon(Icons.Default.QrCode, contentDescription = null)
                                            DemoScreen.VIEWER -> Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                            DemoScreen.EDITOR -> Icon(Icons.Default.Edit, contentDescription = null)
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
                                DemoScreen.SIMPLE -> SimpleDemoScreen(onView = { uri ->
                                    viewerUri = uri
                                    currentScreen = DemoScreen.VIEWER
                                })
                                DemoScreen.STREAM -> StreamDemoScreen()
                                DemoScreen.RESIZING -> ResizingDemoScreen()
                                DemoScreen.PANORAMA -> PanoramaDemoScreen()
                                DemoScreen.QR_CODE -> QrCodeDemoScreen()
                                DemoScreen.VIEWER -> ViewerDemoScreen(initialUri = viewerUri)
                                DemoScreen.EDITOR -> EditorDemoScreen(initialUri = viewerUri)
                            }
                        }
                    }
                }
            }
        }
    }
}

@RequiresPermission(Manifest.permission.CAMERA)
@Composable
fun SimpleDemoScreen(onView: (Uri) -> Unit = {}) {
    val context = LocalContext.current
    val cameraController = remember { mutableStateOf<CameraController?>(null) }
    val cameraState = remember { mutableStateOf<CameraState?>(null) }
    // A DNG beside each JPEG, where the camera can write one.
    var raw by remember { mutableStateOf(false) }
    val rawSupported = cameraState.value?.supportedPictureFormats?.contains(PictureFormat.RAW_JPEG) == true
    // The last picture filed; with RAW on, the DNG, which is filed after the JPEG.
    var lastSaved by remember { mutableStateOf<Uri?>(null) }
    var hasRequiredPermissions by remember { mutableStateOf(false) }
    var uiStatusText by remember { mutableStateOf("") }

    val allPermissions = remember { (Permissions.REQUIRED_PERMISSIONS + Permissions.OPTIONAL_PERMISSIONS).distinct().toTypedArray() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
        hasRequiredPermissions = Permissions.REQUIRED_PERMISSIONS.all { permissionsMap[it] == true }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(allPermissions) }

    // A burst runs for as long as its button is held, into a directory of the app's own: the
    // pictures are kept for the app to work with rather than filed in the gallery.
    val burstDirectory = remember { File(context.cacheDir, "burst") }
    val burstInteractions = remember { MutableInteractionSource() }
    val burstPressed by burstInteractions.collectIsPressedAsState()
    LaunchedEffect(burstPressed) {
        val controller = cameraController.value ?: return@LaunchedEffect
        if (!burstPressed) {
            controller.stopBurst()
            return@LaunchedEffect
        }
        burstDirectory.listFiles()?.forEach { it.delete() }
        var taken = 0
        controller.startBurst(
            burstDirectory,
            MAX_BURST_PICTURES,
            onImageCaptured = { uiStatusText = "Burst: ${++taken}" },
            onFinished = { count -> uiStatusText = "Burst took $count pictures" }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (uiStatusText.isNotEmpty()) {
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
        }

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
                    pictureFormat = if (raw && rawSupported) PictureFormat.RAW_JPEG else PictureFormat.JPEG,
                    state = cameraState,
                    onImageConfirmation = { uiStatusText = "Confirm Image?" },
                    onImageSaved = { uri ->
                        uiStatusText = "Saved: $uri"
                        lastSaved = uri
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Toggle Camera", fontWeight = FontWeight.SemiBold)
                }

                // Held rather than tapped, so it reports presses instead of clicks.
                FilledTonalIconButton(
                    onClick = {},
                    enabled = cameraController.value != null,
                    interactionSource = burstInteractions
                ) {
                    Icon(Icons.Default.BurstMode, contentDescription = "Burst")
                }

                Button(
                    onClick = {
                        uiStatusText = "Capturing..."
                        cameraController.value?.takePicture()
                    },
                    enabled = cameraController.value != null,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Take Picture", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (rawSupported) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = raw, onCheckedChange = { raw = it })
                        Text("RAW", color = Color.White)
                    }
                }
                Button(
                    onClick = { lastSaved?.let(onView) },
                    enabled = lastSaved != null,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View", fontWeight = FontWeight.SemiBold)
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

@RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
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

@RequiresPermission(Manifest.permission.CAMERA)
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

    val boxPadding by animateDpAsState(targetValue = if (isResized) 16.dp else 0.dp, animationSpec = tween(400), label = "padding")
    val boxWidth by animateDpAsState(targetValue = if (isResized) 180.dp else 360.dp, animationSpec = tween(400), label = "width")
    val boxHeight by animateDpAsState(targetValue = if (isResized) 120.dp else 500.dp, animationSpec = tween(400), label = "height")
    val horizontalBias by animateFloatAsState(targetValue = if (isResized) 1f else 0f, animationSpec = tween(400), label = "hBias")
    val verticalBias by animateFloatAsState(targetValue = if (isResized) 1f else -1f, animationSpec = tween(400), label = "vBias")
    val cornerRadius by animateDpAsState(targetValue = if (isResized) 12.dp else 24.dp, animationSpec = tween(400), label = "cornerRadius")

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (hasRequiredPermissions) {
            Box(
                modifier = Modifier
                    .size(width = boxWidth, height = boxHeight)
                    .align(androidx.compose.ui.BiasAlignment(horizontalBias, verticalBias))
                    .padding(boxPadding)
                    .clip(RoundedCornerShape(cornerRadius))
            ) {
                Camera(
                    modifier = Modifier.fillMaxSize(),
                    controller = cameraController
                )
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

/**
 * Sweeps a panorama or a photosphere. The camera draws the guide over its own preview, so this is
 * only the controls and the result.
 */
@RequiresPermission(Manifest.permission.CAMERA)
@Composable
fun PanoramaDemoScreen() {
    val cameraController = remember { mutableStateOf<CameraController?>(null) }
    val cameraState = remember { mutableStateOf<CameraState?>(null) }
    var hasRequiredPermissions by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(PanoramaMode.PANORAMA) }
    var status by remember { mutableStateOf("Turn on the spot; for a photosphere, hold the circle on each dot") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
        hasRequiredPermissions = Permissions.REQUIRED_PERMISSIONS.all { permissionsMap[it] == true }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(Permissions.REQUIRED_PERMISSIONS) }

    val sweeping = cameraState.value?.isCapturingPanorama == true
    // Aiming straight up lays the phone face down, which turns the screen off on a phone that
    // watches for it, and with it the camera.
    val view = LocalView.current
    DisposableEffect(sweeping) {
        view.keepScreenOn = sweeping
        onDispose { view.keepScreenOn = false }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = status,
                modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, color = Color.White)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (hasRequiredPermissions) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))) {
                Camera(
                    modifier = Modifier.fillMaxSize(),
                    controller = cameraController,
                    state = cameraState
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(
                    onClick = {
                        mode = if (mode == PanoramaMode.PANORAMA) PanoramaMode.PHOTOSPHERE else PanoramaMode.PANORAMA
                    },
                    enabled = !sweeping,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(if (mode == PanoramaMode.PANORAMA) "Panorama" else "Photosphere", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        val controller = cameraController.value ?: return@Button
                        if (sweeping) {
                            controller.stopPanorama()
                            return@Button
                        }
                        val started = controller.startPanorama(
                            mode,
                            onProgress = { status = "${Math.round(it * 100)}% covered" },
                            onSaved = { uri -> status = "Saved to $uri" },
                            onFailed = { status = "Nothing was captured" })
                        if (!started) {
                            status = "This device cannot report which way it is pointing"
                        }
                    },
                    enabled = cameraState.value != null,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Panorama, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (sweeping) "Done" else "Start", fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Button(onClick = { permissionLauncher.launch(Permissions.REQUIRED_PERMISSIONS) }) {
                Text("Request Permission")
            }
        }
    }
}

@RequiresPermission(Manifest.permission.CAMERA)
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

/**
 * Shows any picture, video or recording in the library's [Media], which works out what it is,
 * with a picker to open another, a switch to show it as something else, and a line saying what
 * the library's MediaTags reads from it.
 */
@Composable
fun ViewerDemoScreen(initialUri: Uri?) {
    val context = LocalContext.current
    var uri by remember(initialUri) { mutableStateOf(initialUri) }
    var mode by remember(uri) { mutableStateOf(ViewerMode.AUTO) }
    var shown by remember(uri) { mutableStateOf(ViewerMode.AUTO) }
    var gyroscope by remember { mutableStateOf(false) }
    var status by remember(uri, mode) {
        mutableStateOf(if (uri == null) "Take a picture, or open any picture, video or recording" else "")
    }
    var summary by remember(uri) { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
        }
    }
    val hasRotationSensor = remember { hasRotationSensor(context) }
    LaunchedEffect(uri) {
        val current = uri ?: return@LaunchedEffect
        summary = withContext(Dispatchers.IO) { describeMedia(context, current) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Media(
                modifier = Modifier.fillMaxSize(),
                uri = uri,
                type = mode.type,
                gyroscope = gyroscope,
                onLoaded = { type -> shown = ViewerMode.of(type); status = "" },
                onFailed = { status = "This device cannot show this file" }
            )
            if (uri != null) {
                Text(
                    listOf(shown.label, summary).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0x80000000)).padding(8.dp)
                )
            }
            if (status.isNotEmpty()) {
                Text(status, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        // Scrolls rather than wraps where the screen is too narrow for every choice.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*")) }) { Text("Open") }
            ViewerMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { mode = option },
                    label = { Text(option.label) }
                )
            }
            if ((shown == ViewerMode.PHOTOSPHERE || shown == ViewerMode.SPHERICAL) && hasRotationSensor) {
                Checkbox(checked = gyroscope, onCheckedChange = { gyroscope = it })
                Text("Gyro", color = Color.White)
            }
        }
    }
}

/** What MediaTags reads from a file that is worth a line on screen. */
private fun describeMedia(context: Context, uri: Uri): String = try {
    describeMedia(MediaTags.open(context, uri))
} catch (e: IOException) {
    "No metadata"
}

/** When, where, what and how special a file is, as its metadata says. */
fun describeMedia(tags: MediaTags): String {
    val parts = mutableListOf<String>()
    tags.title?.let { parts += it }
    listOfNotNull(tags.make, tags.model).joinToString(" ").takeIf { it.isNotEmpty() }?.let { parts += it }
    if (tags.captureTime != -1L) {
        parts += SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(tags.captureTime))
    }
    tags.location?.let { parts += String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) }
    if (tags.gainMap != MediaTags.GAIN_MAP_NONE || tags.videoTransfer == MediaTags.TRANSFER_HLG
        || tags.videoTransfer == MediaTags.TRANSFER_PQ) {
        parts += "HDR"
    }
    if (tags.motionPhoto != null) {
        parts += "Motion photo"
    }
    if (tags.isAnimated) {
        parts += "Animated"
    }
    return if (parts.isEmpty()) "No metadata" else parts.joinToString(" · ")
}

private fun hasRotationSensor(context: Context): Boolean {
    val sensors = context.getSystemService(SensorManager::class.java) ?: return false
    return sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
        || sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
}
