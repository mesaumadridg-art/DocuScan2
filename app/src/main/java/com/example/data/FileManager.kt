package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileManager(private val context: Context) {

    private val scansDir: File
        get() = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }

    private val pdfsDir: File
        get() = File(context.filesDir, "pdfs").apply { if (!exists()) mkdirs() }

    suspend fun saveBitmapToFile(
        bitmap: Bitmap,
        prefix: String = "scan",
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 90
    ): String = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val file = File(scansDir, "${prefix}_${timeStamp}.$extension")
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
        file.absolutePath
    }

    suspend fun loadBitmapFromFile(path: String, maxDimension: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) return@withContext null

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDimension || options.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun copyUriToInternalStorage(uri: Uri, prefix: String = "import"): String? = withContext(Dispatchers.IO) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val targetFile = File(scansDir, "${prefix}_${timeStamp}.jpg")
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getNewPdfFile(baseTitle: String): File {
        val safeName = baseTitle.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifBlank { "DocuScan" }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(pdfsDir, "${safeName}_$timeStamp.pdf")
    }

    fun getShareableUri(filePath: String): Uri {
        val file = File(filePath)
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun deleteFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val file = File(path)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }
}
