package com.example.ui.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DocuScanApp
import com.example.data.DocumentPageSpec
import com.example.data.DocumentRepository
import com.example.data.FileManager
import com.example.data.PdfPageSize
import com.example.imaging.DocumentFilter
import com.example.imaging.EdgeDetector
import com.example.imaging.ImageProcessingUtils
import com.example.imaging.QuadCorners
import com.example.ocr.OcrManager
import com.example.pdf.PdfManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val fileManager = FileManager(application)
    private val pdfManager = PdfManager(application, fileManager)
    private val ocrManager = OcrManager(application)
    val repository = DocumentRepository(
        database = (application as DocuScanApp).database,
        fileManager = fileManager,
        pdfManager = pdfManager,
        ocrManager = ocrManager
    )

    private val _pages = MutableStateFlow<List<DraftPage>>(emptyList())
    val pages: StateFlow<List<DraftPage>> = _pages.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveProgress = MutableStateFlow(Pair(0, 0))
    val saveProgress: StateFlow<Pair<Int, Int>> = _saveProgress.asStateFlow()

    // Live preview for current page
    private val _currentPreview = MutableStateFlow<Bitmap?>(null)
    val currentPreview: StateFlow<Bitmap?> = _currentPreview.asStateFlow()

    fun initDraftPages(drafts: List<DraftPage>) {
        _pages.value = drafts
        _currentPageIndex.value = 0
        _isSaving.value = false
        updateCurrentPreview()
    }

    fun selectPage(index: Int) {
        if (index in _pages.value.indices) {
            _currentPageIndex.value = index
            updateCurrentPreview()
        }
    }

    fun updateCurrentCorners(corners: QuadCorners) {
        val idx = _currentPageIndex.value
        val list = _pages.value.toMutableList()
        if (idx in list.indices) {
            list[idx] = list[idx].copy(corners = corners)
            _pages.value = list
            updateCurrentPreview()
        }
    }

    fun autoDetectCurrentEdges() {
        val idx = _currentPageIndex.value
        val list = _pages.value.toMutableList()
        if (idx in list.indices) {
            viewModelScope.launch {
                val detection = EdgeDetector.detectPaperEdges(list[idx].rawBitmap)
                list[idx] = list[idx].copy(
                    corners = detection.corners,
                    isAutoDetected = detection.isDocumentDetected,
                    detectionMessage = detection.message
                )
                _pages.value = list
                updateCurrentPreview()
            }
        }
    }

    fun resetCornersToFull() {
        updateCurrentCorners(
            QuadCorners(
                topLeft = PointF(0.01f, 0.01f),
                topRight = PointF(0.99f, 0.01f),
                bottomRight = PointF(0.99f, 0.99f),
                bottomLeft = PointF(0.01f, 0.99f)
            )
        )
    }

    fun rotateCurrentPage() {
        val idx = _currentPageIndex.value
        val list = _pages.value.toMutableList()
        if (idx in list.indices) {
            val nextRot = (list[idx].rotation + 90) % 360
            list[idx] = list[idx].copy(rotation = nextRot)
            _pages.value = list
            updateCurrentPreview()
        }
    }

    fun setCurrentFilter(filter: DocumentFilter) {
        val idx = _currentPageIndex.value
        val list = _pages.value.toMutableList()
        if (idx in list.indices) {
            list[idx] = list[idx].copy(filter = filter)
            _pages.value = list
            updateCurrentPreview()
        }
    }

    fun setCurrentBrightness(brightness: Float) {
        val idx = _currentPageIndex.value
        val list = _pages.value.toMutableList()
        if (idx in list.indices) {
            list[idx] = list[idx].copy(brightness = brightness)
            _pages.value = list
            updateCurrentPreview()
        }
    }

    fun setCurrentContrast(contrast: Float) {
        val idx = _currentPageIndex.value
        val list = _pages.value.toMutableList()
        if (idx in list.indices) {
            list[idx] = list[idx].copy(contrast = contrast)
            _pages.value = list
            updateCurrentPreview()
        }
    }

    fun deleteCurrentPage() {
        val list = _pages.value.toMutableList()
        val idx = _currentPageIndex.value
        if (idx in list.indices) {
            list.removeAt(idx)
            _pages.value = list
            _currentPageIndex.value = if (idx >= list.size) (list.size - 1).coerceAtLeast(0) else idx
            updateCurrentPreview()
        }
    }

    fun addPages(newDrafts: List<DraftPage>) {
        _pages.value = _pages.value + newDrafts
        updateCurrentPreview()
    }

    private fun updateCurrentPreview() {
        val idx = _currentPageIndex.value
        val list = _pages.value
        if (idx !in list.indices) {
            _currentPreview.value = null
            return
        }

        val draft = list[idx]
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val rotated = if (draft.rotation != 0) {
                    ImageProcessingUtils.rotateBitmap(draft.rawBitmap, draft.rotation)
                } else {
                    draft.rawBitmap
                }
                val warped = ImageProcessingUtils.warpPerspective(rotated, draft.corners)
                val filtered = ImageProcessingUtils.applyAdjustmentsAndFilter(
                    source = warped,
                    filter = draft.filter,
                    brightness = draft.brightness,
                    contrast = draft.contrast
                )
                withContext(Dispatchers.Main) {
                    _currentPreview.value = filtered
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveDocument(
        title: String,
        folderId: Long? = null,
        pageSize: PdfPageSize = PdfPageSize.LETTER,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (_pages.value.isEmpty()) {
            onError("No hay páginas para guardar")
            return
        }
        _isSaving.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val docTitle = title.trim().ifBlank {
                    "DocuScan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
                }
                val pageSpecs = _pages.value.map { draft ->
                    DocumentPageSpec(
                        rawBitmap = draft.rawBitmap,
                        corners = draft.corners,
                        filter = draft.filter,
                        brightness = draft.brightness,
                        contrast = draft.contrast,
                        rotationDegrees = draft.rotation
                    )
                }

                val docId = repository.createDocument(
                    title = docTitle,
                    pages = pageSpecs,
                    folderId = folderId,
                    pageSize = pageSize
                )

                withContext(Dispatchers.Main) {
                    _isSaving.value = false
                    onSuccess(docId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _isSaving.value = false
                    onError(e.localizedMessage ?: "Error al guardar el documento")
                }
            }
        }
    }
}
