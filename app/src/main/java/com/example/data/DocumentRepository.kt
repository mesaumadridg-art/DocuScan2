package com.example.data

import android.content.Context
import android.graphics.Bitmap
import com.example.imaging.DocumentFilter
import com.example.imaging.ImageProcessingUtils
import com.example.imaging.QuadCorners
import com.example.ocr.OcrManager
import com.example.pdf.PdfExportConfig
import com.example.pdf.PdfManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DocumentPageSpec(
    val rawBitmap: Bitmap,
    val corners: QuadCorners,
    val filter: DocumentFilter = DocumentFilter.DOC_ENHANCE,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val rotationDegrees: Int = 0
)

class DocumentRepository(
    private val database: DocuScanDatabase,
    private val fileManager: FileManager,
    private val pdfManager: PdfManager,
    private val ocrManager: OcrManager
) {
    private val docDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val folderDao = database.folderDao()

    val allActiveDocuments: Flow<List<DocumentEntity>> = docDao.getAllActiveDocuments()
    val favoriteDocuments: Flow<List<DocumentEntity>> = docDao.getFavoriteDocuments()
    val trashDocuments: Flow<List<DocumentEntity>> = docDao.getTrashDocuments()
    val allFolders: Flow<List<FolderEntity>> = folderDao.getAllFolders()

    fun searchDocuments(query: String): Flow<List<DocumentEntity>> = docDao.searchDocuments(query)

    fun getDocumentsByFolder(folderId: Long): Flow<List<DocumentEntity>> = docDao.getDocumentsByFolder(folderId)

    fun getDocumentFlow(id: Long): Flow<DocumentEntity?> = docDao.getDocumentFlow(id)

    suspend fun getDocumentById(id: Long): DocumentEntity? = docDao.getDocumentById(id)

    fun getPagesForDocument(documentId: Long): Flow<List<PageEntity>> = pageDao.getPagesForDocument(documentId)

    suspend fun getPagesList(documentId: Long): List<PageEntity> = pageDao.getPagesListForDocument(documentId)

    suspend fun createDocument(
        title: String,
        pages: List<DocumentPageSpec>,
        folderId: Long? = null,
        pageSize: PdfPageSize = PdfPageSize.LETTER
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val safeTitle = title.trim().ifBlank {
            "DocuScan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        }

        val docId = docDao.insertDocument(
            DocumentEntity(
                title = safeTitle,
                createdAt = now,
                updatedAt = now,
                folderId = folderId,
                pageCount = pages.size
            )
        )

        val pageEntities = mutableListOf<PageEntity>()
        var firstThumbnailPath: String? = null

        for ((index, spec) in pages.withIndex()) {
            val rotatedRaw = if (spec.rotationDegrees != 0) {
                ImageProcessingUtils.rotateBitmap(spec.rawBitmap, spec.rotationDegrees)
            } else {
                spec.rawBitmap
            }

            val rawPath = fileManager.saveBitmapToFile(rotatedRaw, prefix = "raw_${docId}_p$index")

            // Process image: warp perspective + apply filter & adjustments
            val warped = ImageProcessingUtils.warpPerspective(rotatedRaw, spec.corners)
            val filtered = ImageProcessingUtils.applyAdjustmentsAndFilter(
                source = warped,
                filter = spec.filter,
                brightness = spec.brightness,
                contrast = spec.contrast
            )
            val procPath = fileManager.saveBitmapToFile(filtered, prefix = "proc_${docId}_p$index")

            if (firstThumbnailPath == null) {
                firstThumbnailPath = procPath
            }

            pageEntities.add(
                PageEntity(
                    documentId = docId,
                    pageIndex = index,
                    originalImagePath = rawPath,
                    processedImagePath = procPath,
                    filterType = spec.filter.name,
                    brightness = spec.brightness,
                    contrast = spec.contrast,
                    rotationDegrees = spec.rotationDegrees,
                    cropCornersJson = spec.corners.toJson(),
                    ocrText = null
                )
            )
        }

        pageDao.insertPages(pageEntities)

        // Generate initial PDF immediately
        val pdfFile = pdfManager.createPdfFromPages(
            pages = pageEntities,
            config = PdfExportConfig(title = safeTitle, pageSize = pageSize)
        )

        // Update document with thumbnail, pageCount, pdfPath
        docDao.updateDocument(
            DocumentEntity(
                id = docId,
                title = safeTitle,
                createdAt = now,
                updatedAt = now,
                folderId = folderId,
                thumbnailPath = firstThumbnailPath,
                pageCount = pageEntities.size,
                pdfPath = pdfFile.absolutePath,
                combinedOcrText = null
            )
        )

        // Asynchronous background OCR to never freeze or delay saving
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fullOcrTextBuilder = StringBuilder()
                for (p in pageEntities) {
                    val bmp = fileManager.loadBitmapFromFile(p.processedImagePath)
                    if (bmp != null) {
                        val ocrRes = ocrManager.recognizeTextFromBitmap(bmp)
                        if (ocrRes.fullText.isNotBlank()) {
                            pageDao.updatePage(p.copy(ocrText = ocrRes.fullText))
                            fullOcrTextBuilder.append(ocrRes.fullText).append("\n\n")
                        }
                    }
                }
                val fullOcr = fullOcrTextBuilder.toString().trim()
                if (fullOcr.isNotBlank()) {
                    val updatedDoc = docDao.getDocumentById(docId)
                    if (updatedDoc != null) {
                        docDao.updateDocument(updatedDoc.copy(combinedOcrText = fullOcr))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        docId
    }

    suspend fun addPageToDocument(
        documentId: Long,
        rawBitmap: Bitmap,
        corners: QuadCorners,
        filter: DocumentFilter = DocumentFilter.DOC_ENHANCE,
        pageSize: PdfPageSize = PdfPageSize.LETTER
    ): Long = withContext(Dispatchers.IO) {
        val existingPages = pageDao.getPagesListForDocument(documentId)
        val nextIndex = existingPages.size

        val rawPath = fileManager.saveBitmapToFile(rawBitmap, prefix = "raw_${documentId}_p$nextIndex")
        val warped = ImageProcessingUtils.warpPerspective(rawBitmap, corners)
        val filtered = ImageProcessingUtils.applyAdjustmentsAndFilter(
            source = warped,
            filter = filter,
            brightness = 0f,
            contrast = 1f
        )
        val procPath = fileManager.saveBitmapToFile(filtered, prefix = "proc_${documentId}_p$nextIndex")

        val pageId = pageDao.insertPage(
            PageEntity(
                documentId = documentId,
                pageIndex = nextIndex,
                originalImagePath = rawPath,
                processedImagePath = procPath,
                filterType = filter.name,
                cropCornersJson = corners.toJson(),
                ocrText = null
            )
        )

        refreshDocumentPdfAndMetadata(documentId, pageSize)

        // Background OCR for added page
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ocrRes = ocrManager.recognizeTextFromBitmap(filtered)
                if (ocrRes.fullText.isNotBlank()) {
                    val page = pageDao.getPageById(pageId)
                    if (page != null) {
                        pageDao.updatePage(page.copy(ocrText = ocrRes.fullText))
                        refreshDocumentPdfAndMetadata(documentId, pageSize)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        pageId
    }

    suspend fun updatePageProcessing(
        pageId: Long,
        corners: QuadCorners,
        filter: DocumentFilter,
        brightness: Float,
        contrast: Float,
        rotationDegrees: Int,
        pageSize: PdfPageSize = PdfPageSize.LETTER
    ) = withContext(Dispatchers.IO) {
        val page = pageDao.getPageById(pageId) ?: return@withContext
        val rawBitmap = fileManager.loadBitmapFromFile(page.originalImagePath) ?: return@withContext

        // Rotate raw bitmap if needed
        val rotated = if (rotationDegrees != 0) {
            ImageProcessingUtils.rotateBitmap(rawBitmap, rotationDegrees)
        } else {
            rawBitmap
        }

        val warped = ImageProcessingUtils.warpPerspective(rotated, corners)
        val processed = ImageProcessingUtils.applyAdjustmentsAndFilter(
            source = warped,
            filter = filter,
            brightness = brightness,
            contrast = contrast
        )

        // Save over previous processed image
        fileManager.deleteFile(page.processedImagePath)
        val newProcPath = fileManager.saveBitmapToFile(processed, prefix = "proc_${page.documentId}_p${page.pageIndex}")

        val updatedPage = page.copy(
            processedImagePath = newProcPath,
            rotationDegrees = rotationDegrees,
            brightness = brightness,
            contrast = contrast,
            filterType = filter.name,
            cropCornersJson = corners.toJson()
        )
        pageDao.updatePage(updatedPage)

        refreshDocumentPdfAndMetadata(page.documentId, pageSize)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ocrRes = ocrManager.recognizeTextFromBitmap(processed)
                if (ocrRes.fullText.isNotBlank()) {
                    pageDao.updatePage(updatedPage.copy(ocrText = ocrRes.fullText))
                    refreshDocumentPdfAndMetadata(page.documentId, pageSize)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun deletePage(page: PageEntity, pageSize: PdfPageSize = PdfPageSize.LETTER) = withContext(Dispatchers.IO) {
        fileManager.deleteFile(page.originalImagePath)
        fileManager.deleteFile(page.processedImagePath)
        pageDao.deletePage(page)

        // Re-index remaining pages
        val remaining = pageDao.getPagesListForDocument(page.documentId)
        for ((idx, p) in remaining.withIndex()) {
            if (p.pageIndex != idx) {
                pageDao.updatePage(p.copy(pageIndex = idx))
            }
        }

        if (remaining.isEmpty()) {
            docDao.moveToTrash(page.documentId)
        } else {
            refreshDocumentPdfAndMetadata(page.documentId, pageSize)
        }
    }

    suspend fun reorderPages(documentId: Long, reorderedPages: List<PageEntity>, pageSize: PdfPageSize = PdfPageSize.LETTER) = withContext(Dispatchers.IO) {
        for ((idx, page) in reorderedPages.withIndex()) {
            pageDao.updatePage(page.copy(pageIndex = idx))
        }
        refreshDocumentPdfAndMetadata(documentId, pageSize)
    }

    suspend fun refreshDocumentPdfAndMetadata(documentId: Long, pageSize: PdfPageSize = PdfPageSize.LETTER) = withContext(Dispatchers.IO) {
        val doc = docDao.getDocumentById(documentId) ?: return@withContext
        val pages = pageDao.getPagesListForDocument(documentId)
        if (pages.isEmpty()) return@withContext

        // Re-generate PDF
        val pdfFile = pdfManager.createPdfFromPages(
            pages = pages,
            config = PdfExportConfig(title = doc.title, pageSize = pageSize)
        )

        val fullOcr = pages.mapNotNull { it.ocrText }.joinToString("\n\n").trim()

        docDao.updateDocument(
            doc.copy(
                updatedAt = System.currentTimeMillis(),
                thumbnailPath = pages.firstOrNull()?.processedImagePath,
                pageCount = pages.size,
                pdfPath = pdfFile.absolutePath,
                combinedOcrText = fullOcr
            )
        )
    }

    suspend fun renameDocument(id: Long, newTitle: String) {
        val doc = docDao.getDocumentById(id) ?: return
        docDao.updateDocument(doc.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
    }

    suspend fun toggleFavorite(id: Long) = docDao.toggleFavorite(id)

    suspend fun moveToTrash(id: Long) = docDao.moveToTrash(id)

    suspend fun restoreFromTrash(id: Long) = docDao.restoreFromTrash(id)

    suspend fun permanentlyDeleteDocument(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        val pages = pageDao.getPagesListForDocument(doc.id)
        for (p in pages) {
            fileManager.deleteFile(p.originalImagePath)
            fileManager.deleteFile(p.processedImagePath)
        }
        fileManager.deleteFile(doc.pdfPath)
        docDao.deleteDocument(doc)
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        docDao.emptyTrash()
    }

    suspend fun createFolder(name: String, colorHex: String = "#0C4A60"): Long {
        return folderDao.insertFolder(FolderEntity(name = name, colorHex = colorHex))
    }

    suspend fun moveDocumentToFolder(docId: Long, folderId: Long?) {
        val doc = docDao.getDocumentById(docId) ?: return
        docDao.updateDocument(doc.copy(folderId = folderId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun exportPdfToDownloads(pdfFile: File, title: String): android.net.Uri? {
        return fileManager.exportPdfToDownloads(pdfFile, title)
    }

    suspend fun exportImageToGallery(bitmap: Bitmap, title: String, isPng: Boolean = false, pageIndex: Int? = null): android.net.Uri? {
        return fileManager.exportImageToGallery(bitmap, title, isPng, pageIndex)
    }

    suspend fun exportAllPagesAsImages(pages: List<PageEntity>, title: String, isPng: Boolean = false): List<android.net.Uri> {
        return fileManager.exportAllPagesAsImages(pages, title, isPng)
    }

    suspend fun exportTextToDownloads(text: String, title: String): android.net.Uri? {
        return fileManager.exportTextToDownloads(text, title)
    }

    suspend fun exportPagesToZip(pages: List<PageEntity>, title: String, ocrText: String? = null): android.net.Uri? {
        return fileManager.exportPagesToZip(pages, title, ocrText)
    }
}
