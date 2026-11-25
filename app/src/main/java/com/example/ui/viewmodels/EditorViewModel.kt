package com.example.ui.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DocuScanApp
import com.example.data.DocumentPageSpec
import com.example.data.DocumentRepository
import com.example.data.FileManager
import com.example.data.PdfPageSize
import com.example.data.PageLayout
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
            // Al borrar, volver a la página anterior para decidir si se agrega otra.
            _currentPageIndex.value = (idx - 1).coerceIn(0, (list.size - 1).coerceAtLeast(0))
            updateCurrentPreview()
        }
    }

    fun addPages(newDrafts: List<DraftPage>) {
        if (newDrafts.isEmpty()) return
        // Las páginas que llegan sin mensaje de detección (p. ej. desde la cámara, o de un
        // camino nuevo) se detectan acá antes de agregarlas. Antes se sumaban tal cual y la
        // segunda página aparecía con el marco en toda la foto.
        viewModelScope.launch(Dispatchers.IO) {
            val listas = newDrafts.map { d ->
                if (d.detectionMessage.isNotBlank()) d
                else {
                    val det = EdgeDetector.detectPaperEdges(d.rawBitmap)
                    d.copy(
                        corners = det.corners,
                        isAutoDetected = det.isDocumentDetected,
                        detectionMessage = det.message
                    )
                }
            }
            withContext(Dispatchers.Main) {
                _pages.value = _pages.value + listas
                _currentPageIndex.value = _pages.value.lastIndex
                updateCurrentPreview()
            }
        }
    }

    fun clearPages() {
        _pages.value = emptyList()
        _currentPageIndex.value = 0
        _currentPreview.value = null
    }

    /** Agrega páginas elegidas de la galería al documento que se está editando. */
    fun addPagesFromUris(uris: List<Uri>, onComplete: (Int) -> Unit = {}) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val newDrafts = mutableListOf<DraftPage>()
            for (uri in uris) {
                try {
                    val input = app.contentResolver.openInputStream(uri)
                    val bytes = input?.use { it.readBytes() }
                    // Bitmap de trabajo reducido (el original se conserva en bytes para exportar).
                    val bitmap = bytes?.let { ImageProcessingUtils.decodeForWork(it) }
                    if (bitmap != null) {
                        val detection = EdgeDetector.detectPaperEdges(bitmap)
                        newDrafts.add(
                            DraftPage(
                                rawBitmap = bitmap,
                                sourceJpeg = bytes,
                                corners = detection.corners,
                                isAutoDetected = detection.isDocumentDetected,
                                detectionMessage = detection.message
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) {
                if (newDrafts.isNotEmpty()) {
                    addPages(newDrafts)
                    _currentPageIndex.value = _pages.value.lastIndex
                    updateCurrentPreview()
                }
                onComplete(newDrafts.size)
            }
        }
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
        layout: PageLayout = PageLayout.COMPLETA,
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
                val pageSpecs = if (layout == PageLayout.CARNET) {
                    // Carnet: se procesan las páginas y se pegan de dos en dos (frente y
                    // reverso) en una sola hoja, así el PDF no las separa.
                    val procesadas = _pages.value.map { d ->
                        val rot = if (d.rotation != 0) {
                            ImageProcessingUtils.rotateBitmap(d.rawBitmap, d.rotation)
                        } else d.rawBitmap
                        val warped = ImageProcessingUtils.warpPerspective(rot, d.corners)
                        ImageProcessingUtils.applyAdjustmentsAndFilter(
                            source = warped,
                            filter = d.filter,
                            brightness = d.brightness,
                            contrast = d.contrast
                        )
                    }
                    val ratio = when (pageSize) {
                        PdfPageSize.OFICIO -> 936f / 612f
                        PdfPageSize.LEGAL -> 1008f / 612f
                        PdfPageSize.A4 -> 842f / 595f
                        else -> 792f / 612f
                    }
                    val sheetW = 1240
                    val sheetH = (sheetW * ratio).toInt()
                    procesadas.chunked(2).map { par ->
                        val hoja = ImageProcessingUtils.composeCardSheet(
                            front = par[0],
                            back = par.getOrNull(1),
                            sheetWidth = sheetW,
                            sheetHeight = sheetH
                        )
                        DocumentPageSpec(
                            rawBitmap = hoja,
                            corners = QuadCorners(),
                            filter = DocumentFilter.ORIGINAL
                        )
                    }
                } else {
                    _pages.value.map { draft ->
                        DocumentPageSpec(
                            rawBitmap = draft.rawBitmap,
                            corners = draft.corners,
                            filter = draft.filter,
                            brightness = draft.brightness,
                            contrast = draft.contrast,
                            rotationDegrees = draft.rotation,
                            // Con el JPEG original el PDF sale desde la foto completa, no desde
                            // el bitmap de trabajo de 2200 px.
                            rawJpeg = draft.sourceJpeg
                        )
                    }
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

    override fun onCleared() {
        ocrManager.close()
        super.onCleared()
    }
}
