package com.example

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipExporter {
    fun exportToZip(context: Context, pages: List<ScanPage>, fileName: String): File {
        val tempDir = context.cacheDir
        val file = File(tempDir, fileName)
        
        ZipOutputStream(FileOutputStream(file)).use { zipOut ->
            val usedNames = mutableSetOf<String>()
            for ((index, page) in pages.withIndex()) {
                val bmp = page.processedBitmap ?: page.originalBitmap
                
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 93, stream)
                val data = stream.toByteArray()
                
                var entryName = page.fileName
                if (entryName.isEmpty() || entryName == "scan.jpg") {
                    entryName = "scan_${index + 1}.jpg"
                }
                
                if (usedNames.contains(entryName)) {
                    val base = entryName.substringBeforeLast(".")
                    val ext = if (entryName.contains(".")) entryName.substringAfterLast(".") else "jpg"
                    var c = 2
                    while (usedNames.contains("${base}_$c.$ext")) {
                        c++
                    }
                    entryName = "${base}_$c.$ext"
                }
                usedNames.add(entryName)
                
                val zipEntry = ZipEntry(entryName)
                zipOut.putNextEntry(zipEntry)
                zipOut.write(data)
                zipOut.closeEntry()
            }
        }
        return file
    }
}
