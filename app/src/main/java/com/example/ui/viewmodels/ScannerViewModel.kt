package com.example.ui.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DocumentRepository
import com.example.data.FileManager
import com.example.data.PageEntity
import com.example.imaging.DocumentFilter
import com.example.imaging.EdgeDetector
import com.example.imaging.ImageProcessingUtils
import com.example.imaging.QuadCorners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DraftPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val rawBitmap: Bitmap,
    val corners: QuadCorners,
    val filter: DocumentFilter = DocumentFilter.DOC_ENHANCE,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val rotation: Int = 0,
    val isAutoDetected: Boolean = true,
    val detectionMessage: String = ""
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val fileManager = FileManager(application)

    private val _draftPages = MutableStateFlow<List<DraftPage>>(emptyList())
    val draftPages: StateFlow<List<DraftPage>> = _draftPages.asStateFlow()

    private val _flashMode = MutableStateFlow(ImageCapture.FLASH_MODE_OFF)
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    private val _isBackCamera = MutableStateFlow(true)
    val isBackCamera: StateFlow<Boolean> = _isBackCamera.asStateFlow()

    private val _isBatchMode = MutableStateFlow(false)
    val isBatchMode: StateFlow<Boolean> = _isBatchMode.asStateFlow()

    private val _isProcessingCapture = MutableStateFlow(false)
    val isProcessingCapture: StateFlow<Boolean> = _isProcessingCapture.asStateFlow()

    fun toggleFlash() {
        _flashMode.value = when (_flashMode.value) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    fun switchCamera() {
        _isBackCamera.value = !_isBackCamera.value
    }

    fun setBatchMode(batch: Boolean) {
        _isBatchMode.value = batch
    }

    fun clearDrafts() {
        _draftPages.value = emptyList()
    }

    fun removeDraftPage(index: Int) {
        val current = _draftPages.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _draftPages.value = current
        }
    }

    fun processCapturedImageProxy(imageProxy: ImageProxy, onComplete: (DraftPage) -> Unit) {
        _isProcessingCapture.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val buffer: ByteBuffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val rotation = imageProxy.imageInfo.rotationDegrees

                var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rotation != 0) {
                    bitmap = ImageProcessingUtils.rotateBitmap(bitmap, rotation)
                }

                val detection = EdgeDetector.detectPaperEdges(bitmap)
                val draft = DraftPage(
                    rawBitmap = bitmap,
                    corners = detection.corners,
                    isAutoDetected = detection.isDocumentDetected,
                    detectionMessage = detection.message
                )

                withContext(Dispatchers.Main) {
                    _draftPages.value = _draftPages.value + draft
                    _isProcessingCapture.value = false
                    onComplete(draft)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _isProcessingCapture.value = false
                }
            } finally {
                imageProxy.close()
            }
        }
    }

    fun importImageFromUri(uri: Uri, onComplete: () -> Unit) {
        _isProcessingCapture.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    val detection = EdgeDetector.detectPaperEdges(bitmap)
                    val draft = DraftPage(
                        rawBitmap = bitmap,
                        corners = detection.corners,
                        isAutoDetected = detection.isDocumentDetected,
                        detectionMessage = detection.message
                    )
                    withContext(Dispatchers.Main) {
                        _draftPages.value = _draftPages.value + draft
                        _isProcessingCapture.value = false
                        onComplete()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _isProcessingCapture.value = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _isProcessingCapture.value = false
                }
            }
        }
    }

    fun updateDraftPage(index: Int, updated: DraftPage) {
        val current = _draftPages.value.toMutableList()
        if (index in current.indices) {
            current[index] = updated
            _draftPages.value = current
        }
    }
}
