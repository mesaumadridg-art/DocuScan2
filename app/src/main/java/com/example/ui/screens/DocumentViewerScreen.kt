package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.data.ExportFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.DocumentEntity
import com.example.data.PageEntity
import com.example.imaging.QuadCorners
import com.example.ui.util.rememberStoragePermissionGate
import com.example.ui.viewmodels.DocuScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DocumentViewerScreen(
    documentId: Long,
    viewModel: DocuScanViewModel,
    onNavigateBack: () -> Unit,
    onAddPages: (Long) -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val docFlow = remember(documentId) { viewModel.repository.getDocumentFlow(documentId) }
    val document by docFlow.collectAsState(initial = null)

    val pagesFlow = remember(documentId) { viewModel.repository.getPagesForDocument(documentId) }
    val pages by pagesFlow.collectAsState(initial = emptyList())

    val allFolders by viewModel.allFolders.collectAsState()

    var selectedPageIndex by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Vista previa, 1: OCR Texto
    var showRenameDialog by remember { mutableStateOf(false) }
    var newTitleInput by remember { mutableStateOf("") }
    var showFolderDialog by remember { mutableStateOf(false) }
    var ocrSearchQuery by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    if (document == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val currentDoc = document!!
    val currentPage = pages.getOrNull(selectedPageIndex)

    // Rename Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renombrar documento") },
            text = {
                OutlinedTextField(
                    value = newTitleInput,
                    onValueChange = { newTitleInput = it },
                    singleLine = true,
                    label = { Text("Nuevo nombre") },
                    modifier = Modifier.fillMaxWidth().testTag("rename_input_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitleInput.isNotBlank()) {
                            viewModel.renameDocument(currentDoc.id, newTitleInput.trim())
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Folder Move Dialog
    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Mover a carpeta") },
            text = {
                Column {
                    Text("Selecciona una carpeta:")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            viewModel.moveDocumentToFolder(currentDoc.id, null)
                            showFolderDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ninguna (Carpeta raíz)")
                    }
                    allFolders.forEach { folder ->
                        TextButton(
                            onClick = {
                                viewModel.moveDocumentToFolder(currentDoc.id, folder.id)
                                showFolderDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(folder.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var selectedExportFormat by remember { mutableStateOf(ExportFormat.PDF) }
    var selectedShareFormat by remember { mutableStateOf(ExportFormat.PDF) }
    var exportSinglePageOnly by remember { mutableStateOf(false) }
    var shareSinglePageOnly by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    val withStoragePermission = rememberStoragePermissionGate()

    // Dialog for exporting/downloading in multiple formats
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Guardar en el Dispositivo", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Selecciona el formato que deseas descargar:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ExportFormat.entries.forEach { fmt ->
                        val isSelected = selectedExportFormat == fmt
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { selectedExportFormat = fmt }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF0C4A60).copy(alpha = 0.2f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = fmt.iconLabel,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fmt.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = fmt.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if ((selectedExportFormat == ExportFormat.JPG || selectedExportFormat == ExportFormat.PNG) && pages.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Páginas a exportar:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !exportSinglePageOnly,
                                onClick = { exportSinglePageOnly = false },
                                label = { Text("Todas (${pages.size})") }
                            )
                            FilterChip(
                                selected = exportSinglePageOnly,
                                onClick = { exportSinglePageOnly = true },
                                label = { Text("Solo pág. ${selectedPageIndex + 1}") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        withStoragePermission {
                            isExporting = true
                            viewModel.exportDocument(
                            document = currentDoc,
                            format = selectedExportFormat,
                            singlePageOnly = exportSinglePageOnly,
                            currentPageIndex = selectedPageIndex
                        ) { success, message ->
                            isExporting = false
                            showExportDialog = false
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isExporting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688))
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Guardar ${selectedExportFormat.iconLabel}")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    enabled = !isExporting
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Dialog for sharing in multiple formats
    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Compartir Documento", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Selecciona el formato para enviar:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ExportFormat.entries.forEach { fmt ->
                        val isSelected = selectedShareFormat == fmt
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { selectedShareFormat = fmt }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF0C4A60).copy(alpha = 0.2f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = fmt.iconLabel,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fmt.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = fmt.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if ((selectedShareFormat == ExportFormat.JPG || selectedShareFormat == ExportFormat.PNG) && pages.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Páginas a compartir:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !shareSinglePageOnly,
                                onClick = { shareSinglePageOnly = false },
                                label = { Text("Todas (${pages.size})") }
                            )
                            FilterChip(
                                selected = shareSinglePageOnly,
                                onClick = { shareSinglePageOnly = true },
                                label = { Text("Solo pág. ${selectedPageIndex + 1}") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShareDialog = false
                        viewModel.shareDocument(
                            document = currentDoc,
                            format = selectedShareFormat,
                            singlePageOnly = shareSinglePageOnly,
                            currentPageIndex = selectedPageIndex
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C4A60))
                ) {
                    Text("Compartir ${selectedShareFormat.iconLabel}")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top App Bar
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            newTitleInput = currentDoc.title
                            showRenameDialog = true
                        }
                        .padding(horizontal = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentDoc.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Editar nombre",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "${pages.size} ${if (pages.size == 1) "página" else "páginas"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Favorite button
                IconButton(onClick = { viewModel.toggleFavorite(currentDoc.id) }) {
                    Icon(
                        imageVector = if (currentDoc.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorito",
                        tint = if (currentDoc.isFavorite) Color(0xFFFFB703) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Folder move
                IconButton(onClick = { showFolderDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Mover a carpeta",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Tabs: Páginas vs OCR
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Páginas (${pages.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Texto OCR") }
            )
        }

        // Body Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> {
                    // Page preview and carousel
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Main big preview of selected page
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (currentPage != null) {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    AsyncImage(
                                        model = File(currentPage.processedImagePath),
                                        contentDescription = "Página ${selectedPageIndex + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }

                                // Navigation arrows
                                if (selectedPageIndex > 0) {
                                    IconButton(
                                        onClick = { selectedPageIndex-- },
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 8.dp)
                                            .background(Color(0x88000000), CircleShape)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Anterior", tint = Color.White)
                                    }
                                }
                                if (selectedPageIndex < pages.size - 1) {
                                    IconButton(
                                        onClick = { selectedPageIndex++ },
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 8.dp)
                                            .background(Color(0x88000000), CircleShape)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Siguiente", tint = Color.White)
                                    }
                                }
                            } else {
                                Text("No hay páginas disponibles", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // Bottom Page Selector Strip
                        Surface(
                            tonalElevation = 3.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(pages) { idx, p ->
                                        val isSel = idx == selectedPageIndex
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp, 58.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .border(
                                                    width = if (isSel) 2.dp else 1.dp,
                                                    color = if (isSel) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .clickable { selectedPageIndex = idx }
                                        ) {
                                            AsyncImage(
                                                model = File(p.processedImagePath),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Text(
                                                text = "${idx + 1}",
                                                fontSize = 9.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .background(Color(0x99000000))
                                                    .padding(2.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Add page button
                                IconButton(
                                    onClick = { onAddPages(currentDoc.id) },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Añadir página", tint = MaterialTheme.colorScheme.primary)
                                }

                                if (pages.size > 1 && currentPage != null) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                viewModel.repository.deletePage(currentPage)
                                                selectedPageIndex = (selectedPageIndex - 1).coerceAtLeast(0)
                                            }
                                        },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(Color(0x22FF5252), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Eliminar página", tint = Color(0xFFFF5252))
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // OCR Text Tab
                    val ocrText = currentPage?.ocrText ?: currentDoc.combinedOcrText ?: ""
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Texto extraído (${if (currentPage?.ocrText != null) "Página ${selectedPageIndex + 1}" else "Documento"})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Row {
                                IconButton(
                                    onClick = { viewModel.ocrManager.copyToClipboard(ocrText) },
                                    enabled = ocrText.isNotBlank()
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar texto")
                                }
                                IconButton(
                                    onClick = { viewModel.ocrManager.shareText(ocrText, "Texto OCR de ${currentDoc.title}") },
                                    enabled = ocrText.isNotBlank()
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Compartir texto")
                                }
                            }
                        }

                        // Search word in OCR text
                        OutlinedTextField(
                            value = ocrSearchQuery,
                            onValueChange = { ocrSearchQuery = it },
                            placeholder = { Text("Buscar palabra en el texto...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )

                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (ocrText.isNotBlank()) {
                                    Text(
                                        text = ocrText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 22.sp
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "No se detectó texto en esta página.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Intenta usar el filtro «Blanco y negro» o «Doc Mejorado» para aumentar el contraste de las letras.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Action Bar for PDF & Sharing
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Descargar / Exportar en múltiples formatos (PDF, JPG, PNG, TXT, ZIP)
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("download_pdf_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Descargar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Compartir en múltiples formatos (PDF, JPG, PNG, TXT, ZIP)
                Button(
                    onClick = { showShareDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("share_pdf_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C4A60)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Compartir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Abrir PDF
                Button(
                    onClick = { viewModel.openPdf(currentDoc) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_pdf_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B4D8)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Abrir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
