package com.example.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DocuScanApp
import com.example.data.DocumentEntity
import com.example.data.DocumentRepository
import com.example.data.FileManager
import com.example.data.FolderEntity
import com.example.data.PageEntity
import com.example.data.PdfPageSize
import com.example.data.PdfQuality
import com.example.data.PreferencesManager
import com.example.data.ThemeMode
import com.example.data.UserPreferences
import com.example.ocr.OcrManager
import com.example.pdf.PdfExportConfig
import com.example.pdf.PdfManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class DocumentSortOrder(val displayName: String) {
    DATE_DESC("Más reciente"),
    DATE_ASC("Más antiguo"),
    NAME_ASC("Nombre (A-Z)"),
    NAME_DESC("Nombre (Z-A)"),
    PAGES_DESC("Más páginas")
}

enum class LibraryTab {
    ALL, FAVORITES, FOLDERS, TRASH
}

class DocuScanViewModel(application: Application) : AndroidViewModel(application) {

    val fileManager = FileManager(application)
    val pdfManager = PdfManager(application, fileManager)
    val ocrManager = OcrManager(application)
    val preferencesManager = (application as DocuScanApp).preferencesManager

    val repository = DocumentRepository(
        database = (application as DocuScanApp).database,
        fileManager = fileManager,
        pdfManager = pdfManager,
        ocrManager = ocrManager
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow(LibraryTab.ALL)
    val selectedTab = _selectedTab.asStateFlow()

    private val _sortOrder = MutableStateFlow(DocumentSortOrder.DATE_DESC)
    val sortOrder = _sortOrder.asStateFlow()

    private val _selectedFolderId = MutableStateFlow<Long?>(null)
    val selectedFolderId = _selectedFolderId.asStateFlow()

    val userPreferences: StateFlow<UserPreferences> = preferencesManager.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val allFolders: StateFlow<List<FolderEntity>> = repository.allFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val documents: StateFlow<List<DocumentEntity>> = combine(
        _searchQuery,
        _selectedTab,
        _sortOrder,
        _selectedFolderId
    ) { query, tab, sort, folderId ->
        Quadruple(query, tab, sort, folderId)
    }.flatMapLatest { (query, tab, sort, folderId) ->
        val baseFlow = when {
            query.isNotBlank() -> repository.searchDocuments(query.trim())
            tab == LibraryTab.FAVORITES -> repository.favoriteDocuments
            tab == LibraryTab.TRASH -> repository.trashDocuments
            tab == LibraryTab.FOLDERS && folderId != null -> repository.getDocumentsByFolder(folderId)
            else -> repository.allActiveDocuments
        }
        baseFlow.combine(_sortOrder) { list, sortMode ->
            when (sortMode) {
                DocumentSortOrder.DATE_DESC -> list.sortedByDescending { it.updatedAt }
                DocumentSortOrder.DATE_ASC -> list.sortedBy { it.updatedAt }
                DocumentSortOrder.NAME_ASC -> list.sortedBy { it.title.lowercase() }
                DocumentSortOrder.NAME_DESC -> list.sortedByDescending { it.title.lowercase() }
                DocumentSortOrder.PAGES_DESC -> list.sortedByDescending { it.pageCount }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSelectedTab(tab: LibraryTab) {
        _selectedTab.value = tab
    }

    fun setSortOrder(order: DocumentSortOrder) {
        _sortOrder.value = order
    }

    fun selectFolder(folderId: Long?) {
        _selectedFolderId.value = folderId
    }

    fun toggleFavorite(documentId: Long) {
        viewModelScope.launch {
            repository.toggleFavorite(documentId)
        }
    }

    fun renameDocument(documentId: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renameDocument(documentId, newTitle.trim())
        }
    }

    fun moveToTrash(documentId: Long) {
        viewModelScope.launch {
            repository.moveToTrash(documentId)
        }
    }

    fun restoreFromTrash(documentId: Long) {
        viewModelScope.launch {
            repository.restoreFromTrash(documentId)
        }
    }

    fun permanentlyDelete(document: DocumentEntity) {
        viewModelScope.launch {
            repository.permanentlyDeleteDocument(document)
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            repository.createFolder(name)
        }
    }

    fun moveDocumentToFolder(documentId: Long, folderId: Long?) {
        viewModelScope.launch {
            repository.moveDocumentToFolder(documentId, folderId)
        }
    }

    fun sharePdf(document: DocumentEntity) {
        viewModelScope.launch {
            val file = getOrGeneratePdfFile(document)
            if (file != null && file.exists()) {
                pdfManager.sharePdf(file)
            }
        }
    }

    fun openPdf(document: DocumentEntity) {
        viewModelScope.launch {
            val file = getOrGeneratePdfFile(document)
            if (file != null && file.exists()) {
                pdfManager.openPdf(file)
            }
        }
    }

    private suspend fun getOrGeneratePdfFile(document: DocumentEntity): File? {
        val existingPath = document.pdfPath
        if (existingPath != null) {
            val f = File(existingPath)
            if (f.exists() && f.length() > 0) return f
        }
        val pages = repository.getPagesList(document.id)
        if (pages.isEmpty()) return null
        val defaultSize = userPreferences.value.defaultPageSize
        val newFile = pdfManager.createPdfFromPages(pages, PdfExportConfig(title = document.title, pageSize = defaultSize))
        repository.refreshDocumentPdfAndMetadata(document.id, defaultSize)
        return newFile
    }

    fun shareOcrText(document: DocumentEntity) {
        val text = document.combinedOcrText
        if (!text.isNullOrBlank()) {
            ocrManager.shareText(text, "Texto extraído de ${document.title}")
        }
    }

    fun copyOcrText(document: DocumentEntity) {
        val text = document.combinedOcrText
        if (!text.isNullOrBlank()) {
            ocrManager.copyToClipboard(text)
        }
    }

    // Settings actions
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesManager.setThemeMode(mode)
        }
    }

    fun setDefaultPageSize(size: PdfPageSize) {
        viewModelScope.launch {
            preferencesManager.setPageSize(size)
        }
    }

    fun setDefaultQuality(quality: PdfQuality) {
        viewModelScope.launch {
            preferencesManager.setQuality(quality)
        }
    }

    fun setAutoCapture(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setAutoCaptureEnabled(enabled)
        }
    }

    fun setFileNamePrefix(prefix: String) {
        viewModelScope.launch {
            preferencesManager.setFileNamePrefix(prefix)
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
