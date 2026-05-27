package com.example

import android.graphics.Bitmap
import java.util.UUID

data class ScanSettings(
    var threshold: Int = 60,       // 15..95
    var contrast: Int = 28,        // 0..65
    var brightness: Int = 8,       // -25..45
    var shadow: Int = 20,          // 0..100
    var sharpen: Int = 35,         // 0..80
    var saturation: Int = 10       // -50..50
) {
    fun clone() = ScanSettings(threshold, contrast, brightness, shadow, sharpen, saturation)
}

data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    var originalBitmap: Bitmap, // Scaled down to ~2000px max
    var processedBitmap: Bitmap? = null,
    var rotation: Int = 0, // 0, 90, 180, 270
    var settings: ScanSettings = ScanSettings(),
    var colorMode: Boolean = true,
    var fileName: String = "scan.jpg"
)

data class HistorySnap(
    val settings: ScanSettings,
    val colorMode: Boolean,
    val rotation: Int,
    val processedBitmap: Bitmap?
)

data class Preset(
    val label: String,
    val settings: ScanSettings,
    val colorMode: Boolean
)

object Presets {
    val map = mapOf(
        "magic" to Preset("🪄 Magic Clean", ScanSettings(58, 28, 8, 18, 35, 10), true),
        "bw" to Preset("⬛ B&W Doc", ScanSettings(72, 42, 5, 12, 45, -50), false),
        "warm" to Preset("🌅 Warm Paper", ScanSettings(50, 20, 14, 22, 25, 22), true),
        "cool" to Preset("❄️ Cool White", ScanSettings(62, 30, 6, 16, 38, -15), true),
        "contrast" to Preset("💪 High Contrast", ScanSettings(55, 55, 0, 30, 55, 5), true),
        "soft" to Preset("🌸 Soft Glow", ScanSettings(45, 10, 15, 35, 10, -5), true),
        "shadowless" to Preset("🌤️ Shadow Less", ScanSettings(55, 22, 14, 5, 28, 5), true),
        "cleandoc" to Preset("🧼 Clean Doc", ScanSettings(78, 42, 6, 8, 52, 0), true),
        "vintage" to Preset("📜 Vintage", ScanSettings(38, 18, 10, 32, 15, -25), true),
        "coolblue" to Preset("💧 Cool Blue", ScanSettings(60, 28, 3, 14, 40, -30), true)
    )
    
    fun findBestPreset(s: ScanSettings, colorMode: Boolean): String? {
        var best: String? = null
        var bestDist = Double.MAX_VALUE
        for ((key, pr) in map) {
            val ps = pr.settings
            val d = Math.abs(s.threshold - ps.threshold).toDouble() +
                    Math.abs(s.contrast - ps.contrast) +
                    Math.abs(s.brightness - ps.brightness) +
                    Math.abs(s.shadow - ps.shadow) +
                    Math.abs(s.sharpen - ps.sharpen) +
                    Math.abs(s.saturation - ps.saturation) +
                    (if (colorMode == pr.colorMode) 0 else 80)
            if (d < bestDist) {
                bestDist = d
                best = key
            }
        }
        return if (bestDist < 60) best else null
    }
}
