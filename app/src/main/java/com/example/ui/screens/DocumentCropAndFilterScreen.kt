package com.example.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.data.PdfPageSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.imaging.DocumentFilter
import com.example.ui.components.InteractiveCropCanvas
import com.example.ui.viewmodels.EditorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EditModeTab(val title: String) {
    CROP("Recorte"),
    FILTERS("Filtros"),
    ADJUSTMENTS("Ajustes")
}

@Composable
fun DocumentCropAndFilterScreen(
    editorViewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onSaveSuccess: (Long) -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val pages by editorViewModel.pages.collectAsState()
    val currentIndex by editorViewModel.currentPageIndex.collectAsState()
    val isSaving by editorViewModel.isSaving.collectAsState()
    val currentPreview by editorViewModel.currentPreview.collectAsState()

    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(EditModeTab.CROP) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var selectedPageSize by remember { mutableStateOf(PdfPageSize.LETTER) }
    var documentTitleInput by remember {
        mutableStateOf("DocuScan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}")
    }

    if (pages.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No hay páginas seleccionadas", color = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onNavigateBack) {
                    Text("Regresar al escáner")
                }
            }
        }
        return
    }

    val currentPage = pages.getOrNull(currentIndex) ?: pages.first()

    // Save Confirmation Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) showSaveDialog = false },
            title = { Text("Guardar Documento", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Introduce el nombre para tu documento (${pages.size} ${if (pages.size == 1) "página" else "páginas"}):",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = documentTitleInput,
                        onValueChange = { documentTitleInput = it },
                        label = { Text("Nombre del documento") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_doc_title_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tamaño de página:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(PdfPageSize.entries.size) { idx ->
                            val size = PdfPageSize.entries[idx]
                            FilterChip(
                                selected = selectedPageSize == size,
                                onClick = { selectedPageSize = size },
                                label = { Text(size.displayName, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        editorViewModel.saveDocument(
                            title = documentTitleInput,
                            pageSize = selectedPageSize,
                            onSuccess = { docId ->
                                showSaveDialog = false
                                onSaveSuccess(docId)
                            },
                            onError = { err ->
                                Toast.makeText(context, "Error: $err", Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C4A60)),
                    modifier = Modifier.testTag("confirm_save_button")
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Guardar y generar PDF")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSaveDialog = false },
                    enabled = !isSaving
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1419))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .background(Color(0x55FFFFFF), CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Atrás",
                    tint = Color.White
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Editar Página ${currentIndex + 1} de ${pages.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (currentPage.detectionMessage.isNotBlank()) {
                    Text(
                        text = currentPage.detectionMessage,
                        color = Color(0xFF64DFDF),
                        fontSize = 11.sp
                    )
                }
            }

            Button(
                onClick = { showSaveDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688)),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.testTag("save_document_toolbar_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Listo", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // View Mode Tabs
        TabRow(
            selectedTabIndex = activeTab.ordinal,
            containerColor = Color(0xFF131F27),
            contentColor = Color(0xFF64DFDF),
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab.ordinal]),
                    color = Color(0xFF00B4D8)
                )
            }
        ) {
            EditModeTab.entries.forEach { tab ->
                Tab(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    text = {
                        Text(
                            text = tab.title,
                            color = if (activeTab == tab) Color(0xFF64DFDF) else Color(0xFF8E9EA7),
                            fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Main Workspace Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            when (activeTab) {
                EditModeTab.CROP -> {
                    InteractiveCropCanvas(
                        bitmap = currentPage.rawBitmap,
                        corners = currentPage.corners,
                        onCornersChanged = { updatedCorners ->
                            editorViewModel.updateCurrentCorners(updatedCorners)
                        }
                    )
                }

                EditModeTab.FILTERS, EditModeTab.ADJUSTMENTS -> {
                    currentPreview?.let { previewBmp ->
                        Image(
                            bitmap = previewBmp.asImageBitmap(),
                            contentDescription = "Vista previa",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } ?: CircularProgressIndicator(color = Color(0xFF00B4D8))
                }
            }
        }

        // Bottom Contextual Control Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF14222B),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (activeTab) {
                    EditModeTab.CROP -> {
                        // Quick crop tools: Auto detect, Full screen, Rotate
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { editorViewModel.autoDetectCurrentEdges() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64DFDF)),
                                modifier = Modifier.testTag("auto_detect_edges_button")
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Auto Detectar", fontSize = 13.sp)
                            }

                            TextButton(
                                onClick = { editorViewModel.resetCornersToFull() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                modifier = Modifier.testTag("full_page_crop_button")
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Página Completa", fontSize = 13.sp)
                            }

                            TextButton(
                                onClick = { editorViewModel.rotateCurrentPage() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                modifier = Modifier.testTag("rotate_page_button")
                            ) {
                                Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rotar 90°", fontSize = 13.sp)
                            }
                        }
                    }

                    EditModeTab.FILTERS -> {
                        // Horizontal filters list
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(DocumentFilter.entries.size) { idx ->
                                val filter = DocumentFilter.entries[idx]
                                val isSelected = currentPage.filter == filter
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { editorViewModel.setCurrentFilter(filter) },
                                    label = { Text(filter.displayName, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF009688),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF1E2E38),
                                        labelColor = Color(0xFFB0C4CE)
                                    ),
                                    border = null
                                )
                            }
                        }
                    }

                    EditModeTab.ADJUSTMENTS -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Brightness slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Brillo", color = Color(0xFFB0C4CE), fontSize = 12.sp, modifier = Modifier.width(70.dp))
                                Slider(
                                    value = currentPage.brightness,
                                    onValueChange = { editorViewModel.setCurrentBrightness(it) },
                                    valueRange = -50f..50f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF64DFDF),
                                        activeTrackColor = Color(0xFF009688),
                                        inactiveTrackColor = Color(0xFF2C3E4B)
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Contrast slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Contraste", color = Color(0xFFB0C4CE), fontSize = 12.sp, modifier = Modifier.width(70.dp))
                                Slider(
                                    value = currentPage.contrast,
                                    onValueChange = { editorViewModel.setCurrentContrast(it) },
                                    valueRange = 0.5f..2.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF64DFDF),
                                        activeTrackColor = Color(0xFF009688),
                                        inactiveTrackColor = Color(0xFF2C3E4B)
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Page Strip (Thumbnails of all pages in batch)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        itemsIndexed(pages) { index, draft ->
                            val isSelected = index == currentIndex
                            Box(
                                modifier = Modifier
                                    .size(54.dp, 64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF64DFDF) else Color(0xFF334B58),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .background(Color.DarkGray)
                                    .clickable { editorViewModel.selectPage(index) }
                            ) {
                                Image(
                                    bitmap = draft.rawBitmap.asImageBitmap(),
                                    contentDescription = "Página ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = "${index + 1}",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .background(Color(0xAA000000), RoundedCornerShape(topStart = 4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Delete current page button (if more than 1 page)
                    if (pages.size > 1) {
                        IconButton(
                            onClick = { editorViewModel.deleteCurrentPage() },
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(40.dp)
                                .background(Color(0x33FF5252), CircleShape)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar página", tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
