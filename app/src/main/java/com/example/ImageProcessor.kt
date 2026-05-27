package com.example

import android.graphics.Bitmap
import android.graphics.Matrix

object ImageProcessor {

    fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    suspend fun processImage(
        src: Bitmap,
        settings: ScanSettings,
        colorMode: Boolean,
        rotation: Int
    ): Bitmap {
        // 1. Rotation first
        val oriented = rotateBitmap(src, rotation)
        val w = oriented.width
        val h = oriented.height
        
        val pixels = IntArray(w * h)
        oriented.getPixels(pixels, 0, w, 0, 0, w, h)

        // 2. Build luminance histogram for peak detection
        val hist = IntArray(256)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (px shr 16) and 0xff
            val g = (px shr 8) and 0xff
            val b = px and 0xff
            val lum = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            hist[lum]++
        }
        
        var peak = 210
        var mx = 0
        for (i in 140..249) {
            if (hist[i] > mx) {
                mx = hist[i]
                peak = i
            }
        }

        // 3. Whitening configuration parameters
        val wp = (peak - (settings.threshold - 20) * 1.25f).toInt().coerceIn(135, 248)
        val cf = 1.0f + (settings.contrast / 100.0f) * 5.5f
        val bf = settings.brightness * 1.6f
        val shf = settings.shadow / 100.0f
        val stf = 1.0f + settings.saturation / 100.0f

        val outPixels = IntArray(w * h)
        
        for (i in pixels.indices) {
            val px = pixels[i]
            var r = (px shr 16) and 0xff
            var g = (px shr 8) and 0xff
            var b = px and 0xff
            val lum = (0.299f * r + 0.587f * g + 0.114f * b)

            if (colorMode) {
                // Color whitening pipeline
                val wf = ((lum - wp) / (255f - wp)).coerceIn(0f, 1f)
                
                fun transformChannel(v: Int): Float {
                    var nv = v + bf
                    nv = 128f + (nv - 128f) * cf
                    nv += (255f - nv) * wf * 0.92f
                    return nv.coerceIn(0f, 255f)
                }

                var rAdj = transformChannel(r)
                var gAdj = transformChannel(g)
                var bAdj = transformChannel(b)

                // Saturation adjustment representing vector distances to luminance grey
                val nvGray = 0.299f * rAdj + 0.587f * gAdj + 0.114f * bAdj
                rAdj = (nvGray + (rAdj - nvGray) * stf).coerceIn(0f, 255f)
                gAdj = (nvGray + (gAdj - nvGray) * stf).coerceIn(0f, 255f)
                bAdj = (nvGray + (bAdj - nvGray) * stf).coerceIn(0f, 255f)

                r = rAdj.toInt()
                g = gAdj.toInt()
                b = bAdj.toInt()
            } else {
                // High contrast Black & White document binarization lookup
                var gray = lum + bf
                gray = 128f + (gray - 128f) * cf
                if (gray > wp) {
                    gray = 255f
                } else if (gray > wp - 28f) {
                    val t = (gray - (wp - 28f)) / 28f
                    gray = (wp - 28f) + t * (255f - (wp - 28f))
                    gray += (255f - gray) * t * 0.55f
                }
                if (gray < 70f) {
                    gray *= 0.55f
                }
                val finGray = gray.coerceIn(0f, 255f).toInt()
                r = finGray
                g = finGray
                b = finGray
            }

            // Shadow-lift calculation to clear scanning ambient shadows
            val nl = 0.299 * r + 0.587 * g + 0.114 * b
            val lf = 1.0 - Math.pow(nl / 255.0, 2.2)
            val adj = Math.min(255.0, nl + shf * lf * 100.0)
            if (adj > nl && nl > 0.0) {
                val sf = adj / nl
                r = Math.min(255.0, r * sf).toInt()
                g = Math.min(255.0, g * sf).toInt()
                b = Math.min(255.0, b * sf).toInt()
            }

            outPixels[i] = (0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
        }

        // 4. Sharpness cross-filter convolution kernel
        if (settings.sharpen > 0) {
            val strength = (settings.sharpen / 100.0f) * 0.7f
            val sharpened = IntArray(w * h)
            val k0 = -strength
            val k1 = 1f + 4f * strength
            
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    val idx = offset + x
                    if (x == 0 || x == w - 1 || y == 0 || y == h - 1) {
                        sharpened[idx] = outPixels[idx]
                        continue
                    }
                    val pC = outPixels[idx]
                    val pL = outPixels[idx - 1]
                    val pR = outPixels[idx + 1]
                    val pT = outPixels[idx - w]
                    val pB = outPixels[idx + w]

                    val rC = (pC shr 16) and 0xff
                    val rL = (pL shr 16) and 0xff
                    val rR = (pR shr 16) and 0xff
                    val rT = (pT shr 16) and 0xff
                    val rB = (pB shr 16) and 0xff
                    val sr = rC * k1 + (rL + rR + rT + rB) * k0

                    val gC = (pC shr 8) and 0xff
                    val gL = (pL shr 8) and 0xff
                    val gR = (pR shr 8) and 0xff
                    val gT = (pT shr 8) and 0xff
                    val gB = (pB shr 8) and 0xff
                    val sg = gC * k1 + (gL + gR + gT + gB) * k0

                    val bC = pC and 0xff
                    val bL = pL and 0xff
                    val bR = pR and 0xff
                    val bT = pT and 0xff
                    val bB = pB and 0xff
                    val sb = bC * k1 + (bL + bR + bT + bB) * k0

                    val finalR = sr.coerceIn(0f, 255f).toInt()
                    val finalG = sg.coerceIn(0f, 255f).toInt()
                    val finalB = sb.coerceIn(0f, 255f).toInt()
                    
                    sharpened[idx] = (0xff000000.toInt()) or (finalR shr 16) or (finalG shr 8) or finalB
                }
            }
            System.arraycopy(sharpened, 0, outPixels, 0, w * h)
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
        return outBmp
    }

    suspend fun aiEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (px shr 16) and 0xff
            val g = (px shr 8) and 0xff
            val b = px and 0xff
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }

        val integral = DoubleArray(w * h)
        val sqIntegral = DoubleArray(w * h)
        for (y in 0 until h) {
            var rowSum = 0.0
            var rowSqSum = 0.0
            val offset = y * w
            for (x in 0 until w) {
                val i = offset + x
                val v = gray[i].toDouble()
                rowSum += v
                rowSqSum += v * v
                integral[i] = (if (y == 0) 0.0 else integral[i - w]) + rowSum
                sqIntegral[i] = (if (y == 0) 0.0 else sqIntegral[i - w]) + rowSqSum
            }
        }

        fun getAreaValue(arr: DoubleArray, x1: Int, y1: Int, x2: Int, y2: Int): Double {
            var valOut = arr[y2 * w + x2]
            if (x1 > 0) valOut -= arr[y2 * w + (x1 - 1)]
            if (y1 > 0) valOut -= arr[(y1 - 1) * w + x2]
            if (x1 > 0 && y1 > 0) valOut += arr[(y1 - 1) * w + (x1 - 1)]
            return valOut
        }

        val ws = Math.max(19, (Math.min(w, h) * 0.04).toInt())
        val half = ws / 2
        val K = 0.25
        val R = 128.0

        val binary = ByteArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                val i = offset + x
                val x1 = Math.max(0, x - half)
                val x2 = Math.min(w - 1, x + half)
                val y1 = Math.max(0, y - half)
                val y2 = Math.min(h - 1, y + half)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                
                val sum = getAreaValue(integral, x1, y1, x2, y2)
                val sqSum = getAreaValue(sqIntegral, x1, y1, x2, y2)
                val mean = sum / area
                val std = Math.sqrt(Math.max(0.0, sqSum / area - mean * mean))
                
                binary[i] = if (gray[i] > mean * (1.0 + K * (std / R - 1.0))) 1 else 0
            }
        }

        val cleaned = ByteArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                val i = offset + x
                if (x < 2 || x >= w - 2 || y < 2 || y >= h - 2) {
                    cleaned[i] = binary[i]
                    continue
                }
                var wc = 0
                for (dy in -1..1) {
                    val neighborOffset = (y + dy) * w
                    for (dx in -1..1) {
                        if (binary[neighborOffset + (x + dx)].toInt() == 1) wc++
                    }
                }
                val b = binary[i].toInt()
                if (b == 0 && wc >= 7) {
                    cleaned[i] = 1
                } else if (b == 1 && wc >= 3) {
                    cleaned[i] = 1
                } else if (b == 1 && wc < 3) {
                    cleaned[i] = 0
                } else {
                    cleaned[i] = binary[i]
                }
            }
        }

        val resultPixels = IntArray(w * h)
        for (i in pixels.indices) {
            val v = if (cleaned[i].toInt() == 1) 255 else 0
            resultPixels[i] = (0xff000000.toInt()) or (v shl 16) or (v shl 8) or v
        }

        // Apply high-quality sharpening pass
        val strength = 0.49f // 0.7 * 0.7
        val sharpened = IntArray(w * h)
        val k0 = -strength
        val k1 = 1f + 4f * strength
        
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                val idx = offset + x
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) {
                    sharpened[idx] = resultPixels[idx]
                    continue
                }
                val pC = resultPixels[idx]
                val pL = resultPixels[idx - 1]
                val pR = resultPixels[idx + 1]
                val pT = resultPixels[idx - w]
                val pB = resultPixels[idx + w]

                val rC = (pC shr 16) and 0xff
                val rL = (pL shr 16) and 0xff
                val rR = (pR shr 16) and 0xff
                val rT = (pT shr 16) and 0xff
                val rB = (pB shr 16) and 0xff
                val sr = rC * k1 + (rL + rR + rT + rB) * k0

                val valFr = sr.coerceIn(0f, 255f).toInt()
                sharpened[idx] = (0xff000000.toInt()) or (valFr shl 16) or (valFr shl 8) or valFr
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(sharpened, 0, w, 0, 0, w, h)
        return outBmp
    }

    suspend fun autoAnalyze(src: Bitmap): ScanSettings {
        val w = src.width
        val h = src.height
        val scale = Math.min(1.0f, 300f / Math.max(w, h))
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        
        val total = sw * sh
        val pixels = IntArray(total)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        
        val hist = IntArray(256)
        var sumL = 0.0
        val lums = DoubleArray(total)
        for (i in 0 until total) {
            val px = pixels[i]
            val r = (px shr 16) and 0xff
            val g = (px shr 8) and 0xff
            val b = px and 0xff
            val L = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            hist[L]++
            sumL += L
            lums[i] = L.toDouble()
        }
        
        val meanL = sumL / total
        var peak = 210
        var pk = 0
        for (i in 145..249) {
            if (hist[i] > pk) {
                pk = hist[i]
                peak = i
            }
        }
        
        var vari = 0.0
        for (i in 0 until total) {
            vari += Math.pow(lums[i] - meanL, 2.0)
        }
        val stdDev = Math.sqrt(vari / total)
        
        var darkCnt = 0
        for (i in 0..54) {
            darkCnt += hist[i]
        }
        val darkPct = (darkCnt.toDouble() / total) * 100.0
        
        val th = Math.round(28f + (255f - peak) * 0.48f).coerceIn(18, 92)
        val ct = Math.round(33f - stdDev.toFloat() * 0.65f).coerceIn(2, 62)
        val br = Math.round((128f - meanL.toFloat()) * 0.38f).coerceIn(-22, 42)
        val shd = Math.round(darkPct.toFloat() * 1.4f + Math.max(0.0f, 128f - meanL.toFloat()) * 0.25f).coerceIn(3, 90)
        val shp = Math.round(25f + stdDev.toFloat() * 0.5f).coerceIn(10, 70)
        
        // Return computed custom settings
        return ScanSettings(
            threshold = th,
            contrast = ct,
            brightness = br,
            shadow = shd,
            sharpen = shp,
            saturation = 10
        )
    }

    suspend fun detectDocumentBounds(src: Bitmap): List<Float> {
        val w = src.width
        val h = src.height
        val scale = Math.min(1.0f, 400f / Math.max(w, h))
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        
        val total = sw * sh
        val pixels = IntArray(total)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        
        val hist = IntArray(256)
        val gray = IntArray(total)
        for (i in 0 until total) {
            val px = pixels[i]
            val r = (px shr 16) and 0xff
            val g = (px shr 8) and 0xff
            val b = px and 0xff
            val v = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            gray[i] = v
            hist[v]++
        }
        
        var wB = 0
        var thresh = 128
        var maxVar = 0.0
        var sum = 0.0
        var sumB = 0.0
        for (i in 0 until 256) {
            sum += i * hist[i]
        }
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val diff = mB - mF
            val v = wB.toDouble() * wF.toDouble() * diff * diff
            if (v > maxVar) {
                maxVar = v
                thresh = t
            }
        }
        
        var minX = sw
        var minY = sh
        var maxX = 0
        var maxY = 0
        for (y in 0 until sh) {
            val offset = y * sw
            for (x in 0 until sw) {
                if (gray[offset + x] > thresh) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        
        if (minX >= maxX || minY >= maxY) {
            return listOf(0.05f, 0.05f, 0.95f, 0.05f, 0.05f, 0.95f, 0.95f, 0.95f)
        }
        
        val dx = (maxX - minX) * 0.04f
        val dy = (maxY - minY) * 0.04f
        
        fun clamp(v: Float) = v.coerceIn(0f, 1f)
        return listOf(
            clamp((minX - dx) / sw), clamp((minY - dy) / sh), // TL (x, y)
            clamp((maxX + dx) / sw), clamp((minY - dy) / sh), // TR (x, y)
            clamp((minX - dx) / sw), clamp((maxY + dy) / sh), // BL (x, y)
            clamp((maxX + dx) / sw), clamp((maxY + dy) / sh)  // BR (x, y)
        )
    }

    fun cropBitmap(src: Bitmap, bounds: List<Float>): Bitmap {
        val iw = src.width
        val ih = src.height
        
        val tlX = bounds[0] * iw
        val tlY = bounds[1] * ih
        val trX = bounds[2] * iw
        val trY = bounds[3] * ih
        val blX = bounds[4] * iw
        val blY = bounds[5] * ih
        val brX = bounds[6] * iw
        val brY = bounds[7] * ih

        val minX = Math.min(Math.min(tlX, trX), Math.min(blX, brX)).toInt().coerceIn(0, iw - 1)
        val minY = Math.min(Math.min(tlY, trY), Math.min(blY, brY)).toInt().coerceIn(0, ih - 1)
        val maxX = Math.max(Math.max(tlX, trX), Math.max(blX, brX)).toInt().coerceIn(0, iw)
        val maxY = Math.max(Math.max(tlY, trY), Math.max(blY, brY)).toInt().coerceIn(0, ih)

        val cropW = (maxX - minX).coerceIn(10, iw)
        val cropH = (maxY - minY).coerceIn(10, ih)

        return Bitmap.createBitmap(src, minX, minY, cropW, cropH)
    }
}
