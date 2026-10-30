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

    suspend fun exportPdfToDownloads(pdfFile: File, title: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val safeName = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifBlank { "DocuScan" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${safeName}_$timeStamp.pdf"

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    pdfFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun exportImageToGallery(
        bitmap: Bitmap,
        title: String,
        isPng: Boolean = false,
        pageIndex: Int? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val safeName = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifBlank { "DocuScan" }
            val pageSuffix = if (pageIndex != null) "_pag_${pageIndex + 1}" else ""
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val extension = if (isPng) "png" else "jpg"
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val fileName = "${safeName}${pageSuffix}_$timeStamp.$extension"

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DocuScan")
                }
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bitmap.compress(format, 92, out)
                }
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun exportAllPagesAsImages(
        pages: List<PageEntity>,
        title: String,
        isPng: Boolean = false
    ): List<Uri> = withContext(Dispatchers.IO) {
        val createdUris = mutableListOf<Uri>()
        for ((index, page) in pages.withIndex()) {
            val bitmap = loadBitmapFromFile(page.processedImagePath)
            if (bitmap != null) {
                val uri = exportImageToGallery(bitmap, title, isPng, pageIndex = index)
                if (uri != null) {
                    createdUris.add(uri)
                }
            }
        }
        createdUris
    }

    suspend fun exportTextToDownloads(text: String, title: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val safeName = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifBlank { "DocuScan" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${safeName}_$timeStamp.txt"

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun createZipArchive(
        pages: List<PageEntity>,
        title: String,
        ocrText: String? = null
    ): File = withContext(Dispatchers.IO) {
        val safeName = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifBlank { "DocuScan" }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(context.cacheDir, "${safeName}_$timeStamp.zip")

        java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            for ((index, page) in pages.withIndex()) {
                val pFile = File(page.processedImagePath)
                if (pFile.exists()) {
                    val entryName = "pagina_${index + 1}.jpg"
                    zipOut.putNextEntry(java.util.zip.ZipEntry(entryName))
                    pFile.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
            if (!ocrText.isNullOrBlank()) {
                zipOut.putNextEntry(java.util.zip.ZipEntry("texto_digitalizado_ocr.txt"))
                zipOut.write(ocrText.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }
        }
        zipFile
    }

    suspend fun exportPagesToZip(
        pages: List<PageEntity>,
        title: String,
        ocrText: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val zipFile = createZipArchive(pages, title, ocrText)
            val contentResolver = context.contentResolver
            val safeName = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifBlank { "DocuScan" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${safeName}_$timeStamp.zip"

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    zipFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun shareImages(imagePaths: List<String>, isPng: Boolean = false, chooserTitle: String = "Compartir imágenes con...") {
        try {
            val uris = ArrayList<Uri>()
            for (path in imagePaths) {
                val f = File(path)
                if (f.exists()) {
                    uris.add(getShareableUri(path))
                }
            }
            if (uris.isEmpty()) return

            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val intent = android.content.Intent(if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                if (uris.size == 1) {
                    putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                }
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(android.content.Intent.createChooser(intent, chooserTitle).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun shareTextFile(text: String, title: String) = withContext(Dispatchers.IO) {
        try {
            val safeName = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifBlank { "DocuScan" }
            val textFile = File(context.cacheDir, "${safeName}_texto.txt")
            textFile.writeText(text, Charsets.UTF_8)
            val uri = getShareableUri(textFile.absolutePath)

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "$title - Texto OCR")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(android.content.Intent.createChooser(intent, "Compartir texto como archivo con...").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun shareZipFile(pages: List<PageEntity>, title: String, ocrText: String? = null) = withContext(Dispatchers.IO) {
        try {
            val zipFile = createZipArchive(pages, title, ocrText)
            val uri = getShareableUri(zipFile.absolutePath)

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "$title.zip")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(android.content.Intent.createChooser(intent, "Compartir archivo ZIP con...").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
