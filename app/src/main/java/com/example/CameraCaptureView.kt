package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraCaptureView(
    onImageCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
    }

    if (permissionState.status.isGranted) {
        CameraPreviewContent(onImageCaptured, onClose)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF3F4F9)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Camera permission is required.",
                    color = Color(0xFF1B1B1F),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { permissionState.launchPermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066F5))
                ) {
                    Text("Grant Permission", color = Color.White)
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onClose) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun CameraPreviewContent(
    onImageCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    var flashEnabled by remember { mutableStateOf(false) }
    var gridEnabled by remember { mutableStateOf(false) }
    var manualMode by remember { mutableStateOf(false) }
    var aspectPreset by remember { mutableStateOf(0) } // 0: Auto/Standard, 1: Square, 2: Document
    
    var isBackCamera by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    
    // Scanner Modes definition matching the screenshot horizontal slider
    val modes = listOf("Id Card", "Passport", "QR Code", "Documents", "Photo")
    var selectedModeIdx by remember { mutableStateOf(1) } // Default to Passport (index 1) exactly as shown in reference

    LaunchedEffect(flashEnabled) {
        imageCapture.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    LaunchedEffect(previewView, isBackCamera) {
        val cameraProviderProvider = ProcessCameraProvider.getInstance(context)
        cameraProviderProvider.addListener({
            val cameraProvider = cameraProviderProvider.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraCaptureView", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Gallery Picker integration for flexible usage
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        onImageCaptured(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraCaptureView", "Error loading gallery picture", e)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // Main layout with elegant outer padding and rounded card containers 
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F9))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. TOP BLUE UTILITY BAR 
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF0066F5))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flash toggler
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (flashEnabled) Color(0xFF0F172A) else Color.White.copy(alpha = 0.15f))
                        .clickable { flashEnabled = !flashEnabled },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash Toggle",
                        tint = if (flashEnabled) Color(0xFFFAC033) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Grid toggler
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (gridEnabled) Color(0xFF0F172A) else Color.White.copy(alpha = 0.15f))
                        .clickable { gridEnabled = !gridEnabled },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GridOn,
                        contentDescription = "Grid Overlay",
                        tint = if (gridEnabled) Color(0xFFFAC033) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Aspect Selection
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (aspectPreset > 0) Color(0xFF0F172A) else Color.White.copy(alpha = 0.15f))
                        .clickable { aspectPreset = (aspectPreset + 1) % 3 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "Aspect Ratio Format",
                        tint = if (aspectPreset > 0) Color(0xFFFAC033) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Manual shutter trigger toggler
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (manualMode) Color(0xFF0F172A) else Color.White.copy(alpha = 0.15f))
                        .clickable { manualMode = !manualMode },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (manualMode) "[M]" else "[A]",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. CENTRAL CAMERA VIEWFINDER (Beautifully rounded card)
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp)),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Live camera feed
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // 3D/Linear Grid overlay layer
                if (gridEnabled) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        
                        // Vertical lines
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(w / 3f, 0f), Offset(w / 3f, h), 1.5.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), 1.5.dp.toPx())
                        
                        // Horizontal lines
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, h / 3f), Offset(w, h / 3f), 1.5.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), 1.5.dp.toPx())
                    }
                }

                // Corner guides and dividers layout matching the image (Passport has top & bottom separate)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Dynamic margin based on selected mode
                    val marginPercentW = if (selectedModeIdx == 2) 0.20f else 0.08f // QR core is smaller square
                    val marginPercentH = if (selectedModeIdx == 2) 0.30f else 0.12f

                    val left = w * marginPercentW
                    val top = h * marginPercentH
                    val right = w - (w * marginPercentW)
                    val bottom = h - (h * marginPercentH)

                    val strokeW = 4.dp.toPx()
                    val cornerLength = 32.dp.toPx()
                    val cornerColor = Color.White

                    // 1. Top-Left L Guide
                    drawLine(cornerColor, Offset(left, top), Offset(left + cornerLength, top), strokeW)
                    drawLine(cornerColor, Offset(left, top), Offset(left, top + cornerLength), strokeW)

                    // 2. Top-Right L Guide
                    drawLine(cornerColor, Offset(right, top), Offset(right - cornerLength, top), strokeW)
                    drawLine(cornerColor, Offset(right, top), Offset(right, top + cornerLength), strokeW)

                    // 3. Bottom-Left L Guide
                    drawLine(cornerColor, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeW)
                    drawLine(cornerColor, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeW)

                    // 4. Bottom-Right L Guide
                    drawLine(cornerColor, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeW)
                    drawLine(cornerColor, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeW)

                    // If Passport (index 1) mode layout is active -> draw the dashed division line matching user image
                    if (selectedModeIdx == 1) {
                        val midY = h / 2f
                        drawLine(
                            color = Color(0xFF0066F5),
                            start = Offset(left, midY),
                            end = Offset(right, midY),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                        )
                    }
                }

                // Oval label badges for Passport mode ("Side A" and "Side B")
                if (selectedModeIdx == 1) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 48.dp),
                            verticalArrangement = Arrangement.SpaceAround,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Upper label Side A
                            Box(
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 18.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "Side A",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Lower label Side B
                            Box(
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 18.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "Side B",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Processing Indicator overlay
                if (capturing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. HORIZONTAL SCANNER MODES SLIDER (Text Tabs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEachIndexed { idx, label ->
                val isActive = idx == selectedModeIdx
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable { selectedModeIdx = idx }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label,
                            color = if (isActive) Color(0xFF0066F5) else Color(0xFF55597D),
                            fontSize = 15.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                        )
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0066F5))
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. BOTTOM BLUE SYSTEM BAR CONTROLS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF0066F5))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel Button link action
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.width(76.dp)
                ) {
                    Text(
                        text = "Cancel",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                // Swap rotation camera button
                IconButton(
                    onClick = { isBackCamera = !isBackCamera },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cached,
                        contentDescription = "Swap Camera Lens",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Shutter Button (white visual cue nested in circular outline ring)
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .clickable {
                            if (!capturing) {
                                capturing = true
                                val photoFile = File(context.cacheDir, "temp_capture.jpg")
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                                
                                imageCapture.takePicture(
                                    outputOptions,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            val originalBmp = BitmapFactory.decodeFile(photoFile.absolutePath)
                                            if (originalBmp != null) {
                                                val finalBmp = fixImageOrientation(photoFile.absolutePath, originalBmp)
                                                // Resize downscale to prevent OOM
                                                val targetDim = 1600f
                                                val scale = Math.min(1f, targetDim / Math.max(finalBmp.width, finalBmp.height))
                                                val finalScaled = if (scale < 1f) {
                                                    Bitmap.createScaledBitmap(
                                                        finalBmp,
                                                        (finalBmp.width * scale).toInt(),
                                                        (finalBmp.height * scale).toInt(),
                                                        true
                                                    )
                                                } else {
                                                    finalBmp
                                                }
                                                onImageCaptured(finalScaled)
                                            }
                                            capturing = false
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("CameraCapture", "Photo Capture failed: ${exception.message}", exception)
                                            capturing = false
                                        }
                                    }
                                )
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                // Gallery picker button 
                IconButton(
                    onClick = {
                        galleryPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = "Import Device gallery page",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Done Button trigger
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.width(76.dp)
                ) {
                    Text(
                        text = "Done",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

fun fixImageOrientation(path: String, src: Bitmap): Bitmap {
    try {
        val exifInterface = android.media.ExifInterface(path)
        val orientation = exifInterface.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_UNDEFINED
        )
        val matrix = Matrix()
        var rotate = 0f
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotate = 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotate = 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotate = 270f
        }
        if (rotate == 0f) return src
        matrix.postRotate(rotate)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    } catch (e: Exception) {
        return src
    }
}

