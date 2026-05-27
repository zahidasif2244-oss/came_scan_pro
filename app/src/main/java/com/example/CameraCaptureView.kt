package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
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
                .background(Color(0xFF0D1117)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Camera permission is required.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionState.launchPermissionRequest() }) {
                    Text("Grant Permission")
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
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(flashEnabled) {
        imageCapture.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    LaunchedEffect(previewView) {
        val cameraProviderProvider = ProcessCameraProvider.getInstance(context)
        cameraProviderProvider.addListener({
            val cameraProvider = cameraProviderProvider.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraCaptureView", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Guide Box Overlay representing an A4 sheet layout
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            val guideW = width * 0.85f
            val guideH = guideW * 1.414f // Standard document A4 aspect ratio 
            val left = (width - guideW) / 2
            val top = (height - guideH) / 2
            
            drawRoundRect(
                color = Color(0xFF0061A4),
                topLeft = Offset(left, top),
                size = Size(guideW, guideH),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                style = Stroke(
                    width = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                )
            )
        }

        // Camera Control HUD over video feed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close Camera", tint = Color.White)
            }

            IconButton(
                onClick = { flashEnabled = !flashEnabled },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flash Toggle",
                    tint = if (flashEnabled) Color(0xFFD1E4FF) else Color.White
                )
            }
        }

        // Action Shutter Button layout
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (capturing) {
                CircularProgressIndicator(color = Color(0xFF0061A4), strokeWidth = 4.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White, CircleShape)
                            .border(1.dp, Color.Gray, CircleShape)
                            .align(Alignment.Center)
                            .clickable {
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
                                                // Downscale large image for JVM efficiency
                                                val maxDim = 1800f
                                                val scale = Math.min(1f, maxDim / Math.max(finalBmp.width, finalBmp.height))
                                                val scaledBmp = if (scale < 1f) {
                                                    Bitmap.createScaledBitmap(
                                                        finalBmp,
                                                        (finalBmp.width * scale).toInt(),
                                                        (finalBmp.height * scale).toInt(),
                                                        true
                                                    )
                                                } else {
                                                    finalBmp
                                                }
                                                onImageCaptured(scaledBmp)
                                            }
                                            capturing = false
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("CameraCapture", "Photo capture failed: ${exception.message}", exception)
                                            capturing = false
                                        }
                                    }
                                )
                            }
                    )
                }
            }
        }
        
        Text(
            text = "Fit document pages inside the guidelines",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Correct orientation issues based on camera EXIF tags
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
