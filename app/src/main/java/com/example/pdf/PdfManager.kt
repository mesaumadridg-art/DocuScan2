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
        val total = pages.size
        if (total == 0) throw IllegalArgumentException("El documento no tiene páginas")

        // Resolución objetivo: la hoja a la resolución elegida, con un mínimo razonable para
        // que el texto siga legible aunque la foto de origen sea pequeña.
        val encoded = ArrayList<PdfWriter.JpegPage>(total)
        var firstW = 612
        var firstH = 792

        for ((index, page) in pages.withIndex()) {
            onProgress(index + 1, total)

            val imageFile = File(page.processedImagePath)
            if (!imageFile.exists()) continue

            // Se decodifica ya reducido: no hace falta cargar 12 MP para dejar 2200 px en el PDF.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue

            val sheetW: Int
            val sheetH: Int
            if (config.pageSize == PdfPageSize.FIT_PAGE) {
                val aspect = bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
                if (aspect > 1f) {
                    sheetW = 842
                    sheetH = (842 / aspect).toInt().coerceAtLeast(1)
                } else {
                    sheetW = (842 * aspect).toInt().coerceAtLeast(1)
                    sheetH = 842
                }
            } else {
                val long = when (config.pageSize) {
                    PdfPageSize.LETTER -> 792
                    PdfPageSize.OFICIO -> 936
                    PdfPageSize.LEGAL -> 1008
                    PdfPageSize.A4 -> 842
                    PdfPageSize.FIT_PAGE -> 792
                }
                val short = when (config.pageSize) {
                    PdfPageSize.LETTER, PdfPageSize.OFICIO, PdfPageSize.LEGAL -> 612
                    PdfPageSize.A4 -> 595
                    PdfPageSize.FIT_PAGE -> 612
                }
                if (config.isLandscape) {
                    sheetW = long
                    sheetH = short
                } else {
                    sheetW = short
                    sheetH = long
                }
            }
            if (index == 0) {
                firstW = sheetW
                firstH = sheetH
            }

            val targetLong = PdfWriter.targetLongestSide(sheetW, sheetH, config.quality.dpi)
            var sample = 1
            while (
                bounds.outWidth / (sample * 2) >= targetLong / 2 ||
                bounds.outHeight / (sample * 2) >= targetLong / 2
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: continue
            val scaled = PdfWriter.fitLongest(decoded, targetLong)
            encoded.add(PdfWriter.encodeJpeg(scaled, config.quality.jpegQuality))
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }

        if (encoded.isEmpty()) throw IllegalStateException("No se pudo leer ninguna página")

        val outputFile = fileManager.getNewPdfFile(config.title)
        PdfWriter.write(
            file = outputFile,
            pages = encoded,
            pageWidthPt = firstW,
            pageHeightPt = firstH,
            title = config.title,
            watermarkText = config.watermarkText
        )
        outputFile
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
