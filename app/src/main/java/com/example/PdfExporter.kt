package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

object PdfExporter {
    fun exportToPdf(context: Context, bitmaps: List<Bitmap>, fileName: String): File {
        val document = PdfDocument()
        
        // A4 Paper format in PDF PostScript points: 595 width x 842 height
        val pageWidth = 595
        val pageHeight = 842
        
        for ((index, bmp) in bitmaps.withIndex()) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            
            // Calculate scale to fit standard A4 aspect ratios while preserving scan properties
            val scaleX = pageWidth.toFloat() / bmp.width
            val scaleY = pageHeight.toFloat() / bmp.height
            val scale = Math.min(scaleX, scaleY)
            
            val drawWidth = bmp.width * scale
            val drawHeight = bmp.height * scale
            val left = (pageWidth - drawWidth) / 2
            val top = (pageHeight - drawHeight) / 2
            
            val destRect = RectF(left, top, left + drawWidth, top + drawHeight)
            
            canvas.drawBitmap(bmp, null, destRect, null)
            document.finishPage(page)
        }
        
        val tempDir = context.cacheDir
        val file = File(tempDir, fileName)
        FileOutputStream(file).use { out ->
            document.writeTo(out)
        }
        document.close()
        return file
    }
}
