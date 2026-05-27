package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.painterResource
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// Coordinate clipping shape for sliding comparison mechanics
class FractionClipShape(val fraction: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        return Outline.Rectangle(Rect(0f, 0f, size.width * fraction, size.height))
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- Toast / Indicator managers ---
    var toastMsg by remember { mutableStateOf("") }
    var toastVisible by remember { mutableStateOf(false) }

    // Custom HUD Toast Launcher
    fun showToast(msg: String) {
        toastMsg = msg
        toastVisible = true
    }

    LaunchedEffect(toastVisible) {
        if (toastVisible) {
            delay(2600)
            toastVisible = false
        }
    }

    // --- State Managers ----
    val pages = remember { mutableStateListOf<ScanPage>() }
    var selectedFolderUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFolderName by remember { mutableStateOf<String?>("Not Selected") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedFolderUri = uri
            selectedFolderName = uri.lastPathSegment ?: "Custom Folder"
            showToast("📁 Folder Linked: $selectedFolderName")
        }
    }
    var activeIdx by remember { mutableIntStateOf(0) }
    var viewMode by remember { mutableStateOf("scanned") } // "scanned" | "original" | "compare" | "crop"
    var cropMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var activePreset by remember { mutableStateOf<String?>("magic") }
    
    // --- Manual Crop Nodes (Normalized 0..1) ---
    var cropTL by remember { mutableStateOf(Offset(0.05f, 0.05f)) }
    var cropTR by remember { mutableStateOf(Offset(0.95f, 0.05f)) }
    var cropBL by remember { mutableStateOf(Offset(0.05f, 0.95f)) }
    var cropBR by remember { mutableStateOf(Offset(0.95f, 0.95f)) }

    // --- Dynamic Slider Values (Mirrors active page selection) ---
    var thresholdVal by remember { mutableIntStateOf(60) }
    var contrastVal by remember { mutableIntStateOf(28) }
    var brightnessVal by remember { mutableIntStateOf(8) }
    var shadowVal by remember { mutableIntStateOf(20) }
    var sharpenVal by remember { mutableIntStateOf(35) }
    var saturationVal by remember { mutableIntStateOf(10) }
    var colorModeVal by remember { mutableStateOf(true) }
    var rotationVal by remember { mutableIntStateOf(0) }

    // --- Compare Slide Divider Position ---
    var cmpPos by remember { mutableFloatStateOf(0.5f) }

    // --- Undo History Management ----
    val undoHistory = remember { mutableStateListOf<HistorySnap>() }
    var histPtr by remember { mutableIntStateOf(-1) }
    val canUndo = histPtr > 0

    // --- Toast / Indicator managers ---

    // Capture View flag
    var showCameraView by remember { mutableStateOf(false) }

    // Resolve Active Document Selection
    val activePage: ScanPage? = if (pages.isEmpty() || activeIdx !in pages.indices) null else pages[activeIdx]

    // Capture Undo History state values
    fun pushHistory() {
        val p = activePage ?: return
        if (histPtr < undoHistory.size - 1) {
            val removeCount = undoHistory.size - 1 - histPtr
            repeat(removeCount) {
                undoHistory.removeAt(undoHistory.size - 1)
            }
        }
        undoHistory.add(
            HistorySnap(
                settings = p.settings.clone(),
                colorMode = p.colorMode,
                rotation = p.rotation,
                processedBitmap = p.processedBitmap
            )
        )
        if (undoHistory.size > 25) {
            undoHistory.removeAt(0)
        }
        histPtr = undoHistory.size - 1
    }

    // Trigger local screen state adjustments to match active page values
    fun syncPageSettings(page: ScanPage) {
        thresholdVal = page.settings.threshold
        contrastVal = page.settings.contrast
        brightnessVal = page.settings.brightness
        shadowVal = page.settings.shadow
        sharpenVal = page.settings.sharpen
        saturationVal = page.settings.saturation
        colorModeVal = page.colorMode
        rotationVal = page.rotation
        activePreset = Presets.findBestPreset(page.settings, page.colorMode)
    }

    // Triggered on active index transformation
    LaunchedEffect(activeIdx, pages.size) {
        val p = activePage
        if (p != null) {
            syncPageSettings(p)
            undoHistory.clear()
            histPtr = -1
            undoHistory.add(
                HistorySnap(
                    settings = p.settings.clone(),
                    colorMode = p.colorMode,
                    rotation = p.rotation,
                    processedBitmap = p.processedBitmap
                )
            )
            histPtr = 0
        }
    }

    // --- Core Reactive Image Processing pipeline with coroutine debouncers ---
    LaunchedEffect(
        activeIdx,
        thresholdVal,
        contrastVal,
        brightnessVal,
        shadowVal,
        sharpenVal,
        saturationVal,
        colorModeVal,
        rotationVal
    ) {
        val page = activePage ?: return@LaunchedEffect
        delay(220) // Debouncer padding to ensure fluid sliding experience
        busy = true
        withContext(Dispatchers.Default) {
            try {
                val currentSettings = ScanSettings(
                    threshold = thresholdVal,
                    contrast = contrastVal,
                    brightness = brightnessVal,
                    shadow = shadowVal,
                    sharpen = sharpenVal,
                    saturation = saturationVal
                )
                
                val outputBmp = ImageProcessor.processImage(
                    src = page.originalBitmap,
                    settings = currentSettings,
                    colorMode = colorModeVal,
                    rotation = rotationVal
                )
                
                // Commit changes to list to trigger composable updates
                page.settings = currentSettings
                page.colorMode = colorModeVal
                page.rotation = rotationVal
                page.processedBitmap = outputBmp
                
                val matchedIdx = pages.indexOfFirst { it.id == page.id }
                if (matchedIdx != -1) {
                    pages[matchedIdx] = page.copy(
                        settings = currentSettings.clone(),
                        colorMode = colorModeVal,
                        rotation = rotationVal,
                        processedBitmap = outputBmp
                    )
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Processing error", e)
            } finally {
                busy = false
            }
        }
    }

    // --- Launchers ----
    val galleryPickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                busy = true
                try {
                    var added = 0
                    for (uri in uris) {
                        context.contentResolver.openInputStream(uri).use { stream ->
                            val rawBmp = BitmapFactory.decodeStream(stream)
                            if (rawBmp != null) {
                                // Downscale huge files safely to save JVM memory
                                val maxDim = 1800f
                                val scale = Math.min(1f, maxDim / Math.max(rawBmp.width, rawBmp.height))
                                val bmp = if (scale < 1f) {
                                    Bitmap.createScaledBitmap(rawBmp, (rawBmp.width * scale).toInt(), (rawBmp.height * scale).toInt(), true)
                                } else {
                                    rawBmp
                                }
                                val p = ScanPage(
                                    originalBitmap = bmp,
                                    fileName = "scan_${System.currentTimeMillis()}_${added + 1}.jpg"
                                )
                                pages.add(p)
                                activeIdx = pages.size - 1
                                added++
                            }
                        }
                    }
                    if (added > 0) {
                        viewMode = "scanned"
                        cropMode = false
                        showToast("Loaded $added document page(s)")
                    }
                } catch (e: Exception) {
                    showToast("❌ Image load path error")
                } finally {
                    busy = false
                }
            }
        }
    }

    // Handle Captured outputs from custom view finder
    if (showCameraView) {
        CameraCaptureView(
            onImageCaptured = { bmp ->
                val p = ScanPage(
                    originalBitmap = bmp,
                    fileName = "scan_${System.currentTimeMillis()}.jpg"
                )
                pages.add(p)
                activeIdx = pages.size - 1
                viewMode = "scanned"
                cropMode = false
                showCameraView = false
                showToast("📸 Snapped: Page ${pages.size}")
            },
            onClose = {
                showCameraView = false
            }
        )
        return
    }

    // Sharing wrapper helping launch Android Intent sharing
    fun launchShare(file: File, mime: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "com.aistudio.camescanner.uoxpdq.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Document via:"))
        } catch (e: Exception) {
            showToast("❌ File sharing failed")
        }
    }

    // --- Action Methods ---
    fun applyPreset(key: String) {
        val p = activePage ?: return
        val pr = Presets.map[key] ?: return
        pushHistory()
        
        // Triggers sliders state change which instantly schedules reprocessing
        thresholdVal = pr.settings.threshold
        contrastVal = pr.settings.contrast
        brightnessVal = pr.settings.brightness
        shadowVal = pr.settings.shadow
        sharpenVal = pr.settings.sharpen
        saturationVal = pr.settings.saturation
        colorModeVal = pr.colorMode
        activePreset = key
        showToast("🎨 Filter: ${pr.label}")
    }

    fun applyToAll() {
        if (pages.size < 2) {
            showToast("⚠️ Add multiple pages to sync adjustments")
            return
        }
        val curSettings = ScanSettings(thresholdVal, contrastVal, brightnessVal, shadowVal, sharpenVal, saturationVal)
        val curColor = colorModeVal
        val curRot = rotationVal

        busy = true
        coroutineScope.launch {
            withContext(Dispatchers.Default) {
                for (i in pages.indices) {
                    if (i == activeIdx) continue
                    val p = pages[i]
                    p.settings = curSettings.clone()
                    p.colorMode = curColor
                    p.rotation = curRot
                    p.processedBitmap = ImageProcessor.processImage(p.originalBitmap, curSettings, curColor, curRot)
                    pages[i] = p.copy(
                        settings = curSettings.clone(),
                        colorMode = curColor,
                        rotation = curRot,
                        processedBitmap = p.processedBitmap
                    )
                }
            }
            busy = false
            showToast("🔗 Sync applied to all ${pages.size} pages")
        }
    }

    fun rotateActive() {
        val p = activePage ?: return
        pushHistory()
        rotationVal = (rotationVal + 90) % 360
        showToast("↩️ Rotated to ${rotationVal}°")
    }

    fun triggerUndo() {
        if (!canUndo) return
        histPtr--
        val snap = undoHistory[histPtr]
        val p = activePage ?: return
        
        thresholdVal = snap.settings.threshold
        contrastVal = snap.settings.contrast
        brightnessVal = snap.settings.brightness
        shadowVal = snap.settings.shadow
        sharpenVal = snap.settings.sharpen
        saturationVal = snap.settings.saturation
        colorModeVal = snap.colorMode
        rotationVal = snap.rotation
        p.processedBitmap = snap.processedBitmap
        activePreset = Presets.findBestPreset(snap.settings, snap.colorMode)
        showToast("↩ Undo successful")
    }

    fun runAutoAnalyze() {
        val p = activePage ?: return
        busy = true
        coroutineScope.launch {
            val comp = ImageProcessor.autoAnalyze(p.originalBitmap)
            pushHistory()
            thresholdVal = comp.threshold
            contrastVal = comp.contrast
            brightnessVal = comp.brightness
            shadowVal = comp.shadow
            sharpenVal = comp.sharpen
            saturationVal = comp.saturation
            activePreset = Presets.findBestPreset(comp, colorModeVal)
            busy = false
            showToast("🤖 Recommended specifications configured")
        }
    }

    fun runAiEnhance() {
        val p = activePage ?: return
        busy = true
        coroutineScope.launch {
            val comp = ImageProcessor.aiEnhance(p.originalBitmap)
            pushHistory()
            
            p.originalBitmap = comp
            thresholdVal = 60
            contrastVal = 28
            brightnessVal = 8
            shadowVal = 20
            sharpenVal = 35
            saturationVal = 10
            colorModeVal = false
            activePreset = "bw"
            p.processedBitmap = comp
            
            val matIdx = pages.indexOfFirst { it.id == p.id }
            if (matIdx != -1) {
                pages[matIdx] = p.copy(originalBitmap = comp, processedBitmap = comp)
            }
            
            busy = false
            showToast("🤖 sauvola-binarized cleanup complete")
        }
    }

    fun runAutoCrop() {
        val p = activePage ?: return
        busy = true
        coroutineScope.launch {
            val bounds = ImageProcessor.detectDocumentBounds(p.originalBitmap)
            cropTL = Offset(bounds[0], bounds[1])
            cropTR = Offset(bounds[2], bounds[3])
            cropBL = Offset(bounds[4], bounds[5])
            cropBR = Offset(bounds[6], bounds[7])
            cropMode = true
            viewMode = "crop"
            busy = false
            showToast("🪄 Bounding coordinates detected")
        }
    }

    fun applyCropAdjustment() {
        val p = activePage ?: return
        pushHistory()
        busy = true
        coroutineScope.launch {
            val cropped = ImageProcessor.cropBitmap(
                src = p.originalBitmap,
                bounds = listOf(cropTL.x, cropTL.y, cropTR.x, cropTR.y, cropBL.x, cropBL.y, cropBR.x, cropBR.y)
            )
            p.originalBitmap = cropped
            rotationVal = 0
            cropMode = false
            viewMode = "scanned"
            
            p.processedBitmap = ImageProcessor.processImage(cropped, p.settings, p.colorMode, 0)
            val matIdx = pages.indexOfFirst { it.id == p.id }
            if (matIdx != -1) {
                pages[matIdx] = p.copy(originalBitmap = cropped, rotation = 0, processedBitmap = p.processedBitmap)
                syncPageSettings(p)
            }
            busy = false
            showToast("✅ Crop applied to canvas")
        }
    }

    fun deletePage(i: Int) {
        if (pages.size == 1) {
            pages.clear()
            activeIdx = 0
            undoHistory.clear()
            histPtr = -1
            viewMode = "scanned"
            cropMode = false
            showToast("🧹 Scans reset")
            return
        }
        pages.removeAt(i)
        if (activeIdx >= pages.size) {
            activeIdx = pages.size - 1
        } else if (i < activeIdx) {
            activeIdx--
        }
        showToast("🗑️ Page removed")
    }

    fun saveAsJpg() {
        val p = activePage ?: return
        val bmp = p.processedBitmap ?: p.originalBitmap
        try {
            val outName = p.fileName
            val file = File(context.cacheDir, outName)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            launchShare(file, "image/jpeg")
            showToast("💾 Shared JPEG: $outName")
        } catch (e: Exception) {
            showToast("❌ Share compile error")
        }
    }

    fun saveAsPdf() {
        val list = pages.map { it.processedBitmap ?: it.originalBitmap }
        if (list.isEmpty()) {
            showToast("⚠️ Nothing to share")
            return
        }
        busy = true
        coroutineScope.launch {
            try {
                val outcomeFile = PdfExporter.exportToPdf(context, list, "document_scan_${System.currentTimeMillis()}.pdf")
                launchShare(outcomeFile, "application/pdf")
                showToast("📕 PDF compiling completed")
            } catch (e: Exception) {
                showToast("❌ PDF packaging failure")
            } finally {
                busy = false
            }
        }
    }

    fun saveAsZip() {
        if (pages.isEmpty()) {
            showToast("⚠️ Nothing to compile")
            return
        }
        busy = true
        coroutineScope.launch {
            try {
                val outcomeFile = ZipExporter.exportToZip(context, pages, "scans_${System.currentTimeMillis()}.zip")
                launchShare(outcomeFile, "application/zip")
                showToast("📦 ZIP archive generated")
            } catch (e: Exception) {
                showToast("❌ ZIP packaging failure")
            } finally {
                busy = false
            }
        }
    }

    fun saveAllAsJpg() {
        if (pages.isEmpty()) {
            showToast("⚠️ Nothing to save")
            return
        }
        busy = true
        coroutineScope.launch(Dispatchers.Default) {
            var saved = 0
            try {
                for (i in pages.indices) {
                    val p = pages[i]
                    val bmp = p.processedBitmap ?: p.originalBitmap
                    val outName = "scan_${System.currentTimeMillis()}_${i + 1}.jpg"
                    
                    val file = File(context.cacheDir, outName)
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, outName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameScanner_JPG")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri).use { stream ->
                            if (stream != null) {
                                bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                            }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        saved++
                    }
                }
                withContext(Dispatchers.Main) {
                    showToast("💾 Saved $saved pages as JPG in Gallery")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("❌ Error saving JPGs: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    busy = false
                }
            }
        }
    }

    fun saveAllAsPng() {
        if (pages.isEmpty()) {
            showToast("⚠️ Nothing to save")
            return
        }
        busy = true
        coroutineScope.launch(Dispatchers.Default) {
            var saved = 0
            try {
                for (i in pages.indices) {
                    val p = pages[i]
                    val bmp = p.processedBitmap ?: p.originalBitmap
                    val outName = "scan_${System.currentTimeMillis()}_${i + 1}.png"
                    
                    val file = File(context.cacheDir, outName)
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, outName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameScanner_PNG")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri).use { stream ->
                            if (stream != null) {
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        saved++
                    }
                }
                withContext(Dispatchers.Main) {
                    showToast("🖼️ Saved $saved pages as PNG in Gallery")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("❌ Error saving PNGs: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    busy = false
                }
            }
        }
    }

    fun saveAllToGallery() {
        if (pages.isEmpty()) {
            showToast("⚠️ Nothing to save")
            return
        }
        busy = true
        coroutineScope.launch(Dispatchers.Default) {
            var saved = 0
            try {
                for (i in pages.indices) {
                    val p = pages[i]
                    val bmp = p.processedBitmap ?: p.originalBitmap
                    val outName = "camescanner_gallery_${System.currentTimeMillis()}_${i + 1}.jpg"
                    
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, outName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CameScanner")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri).use { stream ->
                            if (stream != null) {
                                bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                            }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        saved++
                    }
                }
                withContext(Dispatchers.Main) {
                    showToast("🏞️ Added $saved pages to primary Device Gallery")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("❌ Gallery export error: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    busy = false
                }
            }
        }
    }

    // --- RENDER SCREEN BODY ---
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F9))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (pages.isEmpty()) {
                // 1st Row: Logo and Name of App
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(Color(0xFFFFFFFF), Color(0xFFF3F4F9))
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .border(1.5.dp, Color(0xFF1B1B1F).copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                            .testTag("app_logo"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "App Logo Icon",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(46.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = "Came Scanner",
                            color = Color(0xFF1B1B1F),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "Powered By Ali-Tools Company.",
                            color = Color(0xFF55597D),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2nd Row: Camera App Row
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .clickable { showCameraView = true }
                        .testTag("camera_app_row"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFD1E4FF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xFF0061A4), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Camera Icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Open Camera Scanner",
                                color = Color(0xFF001D36),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Snap clear images from the scanner lens",
                                color = Color(0xFF001D36).copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Go",
                            tint = Color(0xFF0061A4),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3rd Row: Select Photo
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .clickable {
                            galleryPickLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                        .testTag("select_photo_row"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF6D9FF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xFF7B4193), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Collections,
                                    contentDescription = "Gallery Icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Select Photo",
                                color = Color(0xFF2C1339),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Import multiple or single images from your device",
                                color = Color(0xFF2C1339).copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Go",
                            tint = Color(0xFF7B4193),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4th Row: Select Folder Button
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .clickable {
                            try {
                                folderPickerLauncher.launch(null)
                            } catch (e: Exception) {
                                showToast("⚠️ Standard system folder picker not found")
                            }
                        }
                        .testTag("select_folder_row"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE2F1E8)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xFF00897B), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Folder Icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Select Folder",
                                color = Color(0xFF00302D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = if (selectedFolderName == "Not Selected") "Link output directory to save scans" else "Linked: $selectedFolderName",
                                color = Color(0xFF00302D).copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Folder Options",
                            tint = Color(0xFF00897B),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // --- EDITOR SCREEN ENGINE ---
            if (activePage != null) {
                // Toolbar section
                SectionHeader(text = "VIEWING UTILITIES")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp)
                ) {
                    ToolbarButton(
                        text = "✨ Scanned",
                        active = viewMode == "scanned" && !cropMode,
                        onClick = {
                            viewMode = "scanned"
                            cropMode = false
                        }
                    )
                    ToolbarButton(
                        text = "📱 Original",
                        active = viewMode == "original" && !cropMode,
                        onClick = {
                            viewMode = "original"
                            cropMode = false
                        }
                    )
                    ToolbarButton(
                        text = "🔍 Compare",
                        active = viewMode == "compare" && !cropMode,
                        onClick = {
                            viewMode = "compare"
                            cropMode = false
                        }
                    )
                    ToolbarButton(
                        text = "✂️ Crop Screen",
                        active = cropMode,
                        onClick = {
                            cropMode = !cropMode
                            viewMode = if (cropMode) "crop" else "scanned"
                        }
                    )
                    ToolbarButton(text = "🪄 Auto Crop Borders", onClick = { runAutoCrop() })
                    ToolbarButton(text = "🤖 AI Sauvola Cleanup", colorScheme = "violet", onClick = { runAiEnhance() })
                    ToolbarButton(text = "🔧 Auto Specs Tune", colorScheme = "blue", onClick = { runAutoAnalyze() })
                    if (cropMode) {
                        ToolbarButton(text = "✅ Apply Crop Borders", colorScheme = "green", onClick = { applyCropAdjustment() })
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // --- CANVAS DISPLAY CARD (Bento Style White Card) ---
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .border(1.dp, Color(0xFFE1E2E9), RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEF4)), // Neutral contrast frame
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val maxW = maxWidth
                        val maxH = maxHeight

                        val bmp = activePage.originalBitmap
                        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
                        val containerAspect = maxW.value / maxH.value
                        val (drawW, drawH) = if (bmpAspect > containerAspect) {
                            maxW to (maxW / bmpAspect)
                        } else {
                            (maxH * bmpAspect) to maxH
                        }

                        // Compare sliding mechanics
                        if (viewMode == "compare" && activePage.processedBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(drawW, drawH)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            cmpPos = (cmpPos + dragAmount.x / size.width).coerceIn(0.05f, 0.95f)
                                        }
                                    }
                            ) {
                                // Draw original underlying
                                android.graphics.Bitmap.createBitmap(activePage.originalBitmap).let {
                                    androidx.compose.foundation.Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Compare Original drawing",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                
                                // Draw clipped processed layer overlay
                                activePage.processedBitmap?.let {
                                    androidx.compose.foundation.Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Compare Processed drawing",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(FractionClipShape(cmpPos))
                                    )
                                }

                                // Interactive sliding line overlay element
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(3.dp)
                                        .offset(x = drawW * cmpPos - 1.5.dp)
                                        .background(Color.White)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(Color.White, CircleShape)
                                            .align(Alignment.Center)
                                            .border(1.5.dp, Color.Black, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "⟷",
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        } else if (viewMode == "crop") {
                            // Render cropping overlay layout
                            Box(modifier = Modifier.size(drawW, drawH)) {
                                android.graphics.Bitmap.createBitmap(activePage.originalBitmap).let {
                                    androidx.compose.foundation.Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Crop original",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                // Overlay crop path bounds painting
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val sizeW = size.width
                                    val sizeH = size.height
                                    val ptTL = Offset(cropTL.x * sizeW, cropTL.y * sizeH)
                                    val ptTR = Offset(cropTR.x * sizeW, cropTR.y * sizeH)
                                    val ptBL = Offset(cropBL.x * sizeW, cropBL.y * sizeH)
                                    val ptBR = Offset(cropBR.x * sizeW, cropBR.y * sizeH)

                                    val cropPath = Path().apply {
                                        moveTo(ptTL.x, ptTL.y)
                                        lineTo(ptTR.x, ptTR.y)
                                        lineTo(ptBR.x, ptBR.y)
                                        lineTo(ptBL.x, ptBL.y)
                                        close()
                                    }

                                    // Fill inner area semi-transparently
                                    drawPath(
                                        path = cropPath,
                                        color = Color(0xFF0061A4).copy(alpha = 0.15f),
                                        style = Fill
                                    )

                                    // Guide stroke boundary
                                    drawPath(
                                        path = cropPath,
                                        color = Color(0xFF0061A4).copy(alpha = 0.85f),
                                        style = Stroke(
                                            width = 2.5.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        )
                                    )
                                }

                                // Navigation Drag Nodes handles
                                DragHandleNode(pos = cropTL, onDrag = { cropTL = it })
                                DragHandleNode(pos = cropTR, onDrag = { cropTR = it })
                                DragHandleNode(pos = cropBL, onDrag = { cropBL = it })
                                DragHandleNode(pos = cropBR, onDrag = { cropBR = it })
                            }
                        } else {
                            // Standard JPG rendering (Scanned or Original)
                            val showBmp = if (viewMode == "original") activePage.originalBitmap else (activePage.processedBitmap ?: activePage.originalBitmap)
                            androidx.compose.foundation.Image(
                                  bitmap = showBmp.asImageBitmap(),
                                  contentDescription = "Active Document visual layout",
                                  modifier = Modifier.size(drawW, drawH)
                            )
                        }

                        // Spinner progress block
                        if (busy) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF0061A4),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- FILTERS PRESETS COLLECTION BLOCK (Bento Style White Card) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE1E2E9), RoundedCornerShape(24.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        SectionHeader(text = "FILTERS & CLARIFIERS")
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Presets.map.forEach { (key, value) ->
                                val selected = activePreset == key
                                val borderAnim = animateDpAsState(targetValue = if (selected) 1.5.dp else 1.dp, label = "preset border")
                                Box(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (selected) Color(0xFFD1E4FF) else Color(0xFFF3F4F9))
                                        .border(
                                            borderAnim.value,
                                            if (selected) Color(0xFF0061A4) else Color(0xFFE1E2E9),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable { applyPreset(key) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = value.label,
                                        color = if (selected) Color(0xFF001D36) else Color(0xFF44474E),
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- ADJUST SLIDERS BOX (Bento Style White Card) ----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE1E2E9), RoundedCornerShape(24.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        SectionHeader(text = "MANUAL RESOLUTION TUNING")
                        Spacer(modifier = Modifier.height(12.dp))

                        SpecsSlider(label = "🔆 Whitening", value = thresholdVal, display = "$thresholdVal%", range = 15f..95f, onValueChange = { thresholdVal = it.toInt() })
                        SpecsSlider(label = "🌓 Contrast", value = contrastVal, display = "$contrastVal%", range = 0f..65f, onValueChange = { contrastVal = it.toInt() })
                        SpecsSlider(label = "💡 Brightness", value = brightnessVal, display = "$brightnessVal%", range = -25f..45f, onValueChange = { brightnessVal = it.toInt() })
                        SpecsSlider(label = "🌑 Shadow Lift", value = shadowVal, display = "$shadowVal%", range = 0f..100f, onValueChange = { shadowVal = it.toInt() })
                        SpecsSlider(label = "✨ Sharpness", value = sharpenVal, display = "$sharpenVal%", range = 0f..80f, onValueChange = { sharpenVal = it.toInt() })
                        SpecsSlider(label = "🎚️ Saturation", value = saturationVal, display = "${if (saturationVal >= 0) "+" else ""}$saturationVal%", range = -50f..50f, onValueChange = { saturationVal = it.toInt() })
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- ACTIONS PANEL GRID (Beautiful Bento Options) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE1E2E9), RoundedCornerShape(24.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        SectionHeader(text = "SCANNERS ACTIONS")
                        Spacer(modifier = Modifier.height(12.dp))

                        // Multi-row Grid System
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Row 1: Quick AI Tunes
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionGridButton(
                                    text = "🤖 Auto Analyze", 
                                    modifier = Modifier.weight(1f), 
                                    tintColor = Color(0xFF7B4193), 
                                    onClick = { runAutoAnalyze() }
                                )
                                ActionGridButton(
                                    text = "🔗 Apply to All", 
                                    modifier = Modifier.weight(1f), 
                                    tintColor = Color(0xFF0061A4), 
                                    onClick = { applyToAll() }
                                )
                            }
                            // Row 2: Rotating & Undo
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionGridButton(
                                    text = "↩️ Rotate Page", 
                                    modifier = Modifier.weight(1f), 
                                    tintColor = Color(0xFF0061A4), 
                                    onClick = { rotateActive() }
                                )
                                ActionGridButton(
                                    text = "↩ Undo Specs", 
                                    modifier = Modifier.weight(1f), 
                                    tintColor = Color(0xFF55597D), 
                                    enabled = canUndo, 
                                    onClick = { triggerUndo() }
                                )
                            }
                            // Row 3: Professional Exporters
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionGridButton(
                                    text = "📕 Share PDF Book", 
                                    modifier = Modifier.weight(1f), 
                                    isFill = true, 
                                    tintColor = Color(0xFF00897B), 
                                    onClick = { saveAsPdf() }
                                )
                                ActionGridButton(
                                    text = "📦 Export Pages ZIP", 
                                    modifier = Modifier.weight(1f), 
                                    isFill = true, 
                                    tintColor = Color(0xFF0EA5A0), 
                                    onClick = { saveAsZip() }
                                )
                            }
                            // Row 4: Share & Reset
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionGridButton(
                                    text = "💾 Share Page JPG", 
                                    modifier = Modifier.weight(1f), 
                                    isFill = true, 
                                    tintColor = Color(0xFF0061A4), 
                                    onClick = { saveAsJpg() }
                                )
                                ActionGridButton(
                                    text = "🗑️ New Scan Reset", 
                                    modifier = Modifier.weight(1f), 
                                    tintColor = Color(0xFF93000A), 
                                    onClick = { resetAllScans(pages, { activeIdx = 0 }, { viewMode = "scanned" }, { cropMode = false }) }
                                )
                            }
                        }
                    }
                }

                // --- BATCH MULTI-SAVE OPTIONS CARD ---
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFFFFAF0)) // Warm inviting cream bento highlight
                        .border(1.dp, Color(0xFFE1E2E9), RoundedCornerShape(24.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        SectionHeader(text = "BATCH MULTI-SAVE UTILITIES")
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Text(
                            text = "Save all ${pages.size} loaded document photos instantly:",
                            color = Color(0xFF44474E),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionGridButton(
                                    text = "💾 Save All JPG", 
                                    modifier = Modifier.weight(1f), 
                                    isFill = true,
                                    tintColor = Color(0xFFE65100), // Vibrant orange accent
                                    onClick = { saveAllAsJpg() }
                                )
                                ActionGridButton(
                                    text = "🖼️ Save All PNG", 
                                    modifier = Modifier.weight(1f), 
                                    isFill = true,
                                    tintColor = Color(0xFF0D47A1), // Deep high-contrast blue
                                    onClick = { saveAllAsPng() }
                                )
                            }
                            ActionGridButton(
                                text = "🏞️ Save to Device Gallery Folder", 
                                modifier = Modifier.fillMaxWidth(),
                                isFill = true,
                                tintColor = Color(0xFF1B5E20), // Primary green progress indicator
                                onClick = { saveAllToGallery() }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- HISTORIC PAGE PREVIEW STRIP (Framed Bento Container) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE1E2E9), RoundedCornerShape(24.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        SectionHeader(text = "PAGES GALLERY")
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pages.forEachIndexed { index, scanPage ->
                                val selected = index == activeIdx
                                Box(
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(64.dp, 84.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFF3F4F9))
                                            .border(
                                                width = if (selected) 2.5.dp else 1.dp,
                                                color = if (selected) Color(0xFF0061A4) else Color(0xFFE1E2E9),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                activeIdx = index
                                                viewMode = "scanned"
                                                cropMode = false
                                            }
                                    ) {
                                        val currentThumbnail = scanPage.processedBitmap ?: scanPage.originalBitmap
                                        androidx.compose.foundation.Image(
                                            bitmap = currentThumbnail.asImageBitmap(),
                                            contentDescription = "thumbnail",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    }

                                    // Small red cross to delete scan pages
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .offset(x = 46.dp, y = (-6).dp)
                                            .background(Color(0xFFE03E4D), CircleShape)
                                            .border(1.5.dp, Color.White, CircleShape)
                                            .clickable { deletePage(index) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "×",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.offset(y = (-1).dp)
                                        )
                                    }
                                }
                            }

                            // Add button strip access
                            Box(
                                modifier = Modifier
                                    .size(64.dp, 84.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF3F4F9))
                                    .border(1.5.dp, Color(0xFFE1E2E9), RoundedCornerShape(12.dp))
                                    .clickable {
                                        galleryPickLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+",
                                    color = Color(0xFF55597D),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Light
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Detail diagnostics HUD block
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFFE1E2E9))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "📐 ${activePage.originalBitmap.width}×${activePage.originalBitmap.height}   📏 ${(activePage.originalBitmap.byteCount / 1024)} KB",
                        color = Color(0xFF44474E),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Footer block
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "© 2026 Came-Scanner — Powered by Ali-Tools",
                    color = Color(0xFF55597D),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Custom animated HUD Toast Overlay
        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1F)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E2E9)),
                shape = RoundedCornerShape(30.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Text(
                    text = toastMsg,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// Reset wrapper execution clean sweep list assets
fun resetAllScans(
    pagesList: MutableList<ScanPage>,
    resetActiveIdx: () -> Unit,
    resetViewMode: () -> Unit,
    resetCropMode: () -> Unit
) {
    pagesList.clear()
    resetActiveIdx()
    resetViewMode()
    resetCropMode()
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFF44474E),
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    )
}

@Composable
fun ToolbarButton(
    text: String,
    active: Boolean = false,
    colorScheme: String = "",
    onClick: () -> Unit
) {
    val containerColor = when {
        colorScheme == "violet" -> Color(0xFF7B4193)
        colorScheme == "blue" -> Color(0xFF0061A4)
        colorScheme == "green" -> Color(0xFFE8F5E9)
        active -> Color(0xFFD1E4FF)
        else -> Color.White
    }

    val contentColor = when {
        colorScheme == "violet" -> Color.White
        colorScheme == "blue" -> Color.White
        colorScheme == "green" -> Color(0xFF001D36)
        active -> Color(0xFF001D36)
        else -> Color(0xFF1B1B1F)
    }

    val borderStrokeColor = when {
        colorScheme == "violet" -> Color(0xFF7B4193)
        colorScheme == "blue" -> Color(0xFF0061A4)
        colorScheme == "green" -> Color(0xFFC8E6C9)
        active -> Color(0xFF0061A4)
        else -> Color(0xFFE1E2E9)
    }

    Card(
        modifier = Modifier
            .padding(end = 6.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderStrokeColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (active) 2.dp else 1.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun DragHandleNode(
    pos: Offset,
    onDrag: (Offset) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value

        Box(
            modifier = Modifier
                .offset(x = (pos.x * w).dp - 11.dp, y = (pos.y * h).dp - 11.dp)
                .size(22.dp)
                .background(Color(0xFFFF4D6A), CircleShape)
                .border(2.5.dp, Color.White, CircleShape)
                .pointerInput(pos) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(
                            Offset(
                                (pos.x + dragAmount.x / size.width).coerceIn(0f, 1f),
                                (pos.y + dragAmount.y / size.height).coerceIn(0f, 1f)
                            )
                        )
                    }
                }
        )
    }
}

@Composable
fun SpecsSlider(
    label: String,
    value: Int,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFF1B1B1F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = display,
                color = Color(0xFF0061A4),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }

        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF0061A4),
                activeTrackColor = Color(0xFF0061A4),
                inactiveTrackColor = Color(0xFFE1E2E9),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

@Composable
fun ActionGridButton(
    text: String,
    modifier: Modifier = Modifier,
    isFill: Boolean = false,
    tintColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val containerColor = if (isFill) {
        tintColor.copy(alpha = if (enabled) 1.0f else 0.4f)
    } else {
        tintColor.copy(alpha = if (enabled) 0.12f else 0.05f)
    }
    val borderStroke = if (isFill) {
        null
    } else {
        androidx.compose.foundation.BorderStroke(1.5.dp, tintColor.copy(alpha = if (enabled) 0.4f else 0.15f))
    }
    val contentColor = if (isFill) {
        Color.White
    } else {
        tintColor.copy(alpha = if (enabled) 1f else 0.4f)
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = if (isFill) tintColor.copy(alpha = 0.2f) else Color.Transparent,
            disabledContentColor = tintColor.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = borderStroke,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
