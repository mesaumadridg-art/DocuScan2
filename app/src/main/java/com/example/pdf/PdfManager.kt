package com.example.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.example.data.FileManager
import com.example.data.PageEntity
import com.example.data.PdfPageSize
import com.example.data.PdfQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PdfExportConfig(
    val title: String,
    val pageSize: PdfPageSize = PdfPageSize.LETTER,
    val isLandscape: Boolean = false,
    val quality: PdfQuality = PdfQuality.HIGH,
    val watermarkText: String? = null
)

class PdfManager(
    private val context: Context,
    private val fileManager: FileManager
) {

    suspend fun createPdfFromPages(
        pages: List<PageEntity>,
        config: PdfExportConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val total = pages.size

        try {
            for ((index, page) in pages.withIndex()) {
                onProgress(index + 1, total)

                // Load processed image
                val imageFile = File(page.processedImagePath)
                if (!imageFile.exists()) continue

                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: continue

                // Determine target dimensions in PostScript points (1/72 inch)
                val (baseWidth, baseHeight) = when (config.pageSize) {
                    PdfPageSize.LETTER -> Pair(612, 792)     // 8.5 x 11 inches
                    PdfPageSize.OFICIO -> Pair(612, 936)     // 8.5 x 13 inches (tradicional América Latina)
                    PdfPageSize.LEGAL -> Pair(612, 1008)     // 8.5 x 14 inches
                    PdfPageSize.FIT_PAGE -> {
                        // Fit to bitmap aspect ratio with max dimension of 1000pt
                        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                        if (aspect > 1f) Pair(842, (842 / aspect).toInt())
                        else Pair((842 * aspect).toInt(), 842)
                    }
                    PdfPageSize.A4 -> Pair(595, 842)         // 210 x 297 mm
                }

                val pageWidth = if (config.isLandscape) maxOf(baseWidth, baseHeight) else minOf(baseWidth, baseHeight)
                val pageHeight = if (config.isLandscape) minOf(baseWidth, baseHeight) else maxOf(baseWidth, baseHeight)

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = pdfDocument.startPage(pageInfo)
                val canvas = pdfPage.canvas

                // Fill background with white
                canvas.drawColor(Color.WHITE)

                // Calculate aspect-fit destination rect
                val imgWidth = bitmap.width
                val imgHeight = bitmap.height
                val imgAspect = imgWidth.toFloat() / imgHeight.toFloat()
                val pageAspect = pageWidth.toFloat() / pageHeight.toFloat()

                val dstRect = if (imgAspect > pageAspect) {
                    // Fit to width
                    val dstH = (pageWidth / imgAspect).toInt()
                    val top = (pageHeight - dstH) / 2
                    Rect(0, top, pageWidth, top + dstH)
                } else {
                    // Fit to height
                    val dstW = (pageHeight * imgAspect).toInt()
                    val left = (pageWidth - dstW) / 2
                    Rect(left, 0, left + dstW, pageHeight)
                }

                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(bitmap, null, dstRect, paint)

                // Draw Watermark if present
                if (!config.watermarkText.isNullOrBlank()) {
                    val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(45, 12, 74, 96) // subtle petrol blue watermark
                        textSize = (pageWidth / 10f).coerceAtLeast(32f)
                        textAlign = Paint.Align.CENTER
                        style = Paint.Style.FILL
                    }
                    canvas.save()
                    canvas.rotate(-35f, pageWidth / 2f, pageHeight / 2f)
                    canvas.drawText(config.watermarkText, pageWidth / 2f, pageHeight / 2f, wmPaint)
                    canvas.restore()
                }

                pdfDocument.finishPage(pdfPage)
                bitmap.recycle()
            }

            val outputFile = fileManager.getNewPdfFile(config.title)
            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
            outputFile
        } finally {
            pdfDocument.close()
        }
    }

    suspend fun mergePdfFiles(
        pdfFiles: List<File>,
        outputTitle: String
    ): File = withContext(Dispatchers.IO) {
        val newPdf = PdfDocument()
        var pageCounter = 1

        for (file in pdfFiles) {
            if (!file.exists()) continue
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val w = page.width
                val h = page.height
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val pageInfo = PdfDocument.PageInfo.Builder(w, h, pageCounter++).create()
                val pdfPage = newPdf.startPage(pageInfo)
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                newPdf.finishPage(pdfPage)
                bitmap.recycle()
            }
            renderer.close()
            pfd.close()
        }

        val outputFile = fileManager.getNewPdfFile(outputTitle)
        FileOutputStream(outputFile).use { out ->
            newPdf.writeTo(out)
        }
        newPdf.close()
        outputFile
    }

    suspend fun splitPdfFile(
        sourceFile: File,
        targetDirectory: File = context.cacheDir
    ): List<File> = withContext(Dispatchers.IO) {
        val resultFiles = mutableListOf<File>()
        if (!sourceFile.exists()) return@withContext resultFiles

        val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)

        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            val w = page.width
            val h = page.height
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()

            val singleDoc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(w, h, 1).create()
            val pdfPage = singleDoc.startPage(pageInfo)
            pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            singleDoc.finishPage(pdfPage)

            val partFile = File(targetDirectory, "${sourceFile.nameWithoutExtension}_pagina_${i + 1}.pdf")
            FileOutputStream(partFile).use { out ->
                singleDoc.writeTo(out)
            }
            singleDoc.close()
            bitmap.recycle()
            resultFiles.add(partFile)
        }

        renderer.close()
        pfd.close()
        resultFiles
    }

    fun openPdf(file: File) {
        try {
            val uri = fileManager.getShareableUri(file.absolutePath)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir documento con...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sharePdf(file: File) {
        try {
            val uri = fileManager.getShareableUri(file.absolutePath)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir PDF vía").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shareImages(imagePaths: List<String>) {
        try {
            val uris = ArrayList<Uri>()
            for (path in imagePaths) {
                uris.add(fileManager.getShareableUri(path))
            }
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir imágenes con...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
