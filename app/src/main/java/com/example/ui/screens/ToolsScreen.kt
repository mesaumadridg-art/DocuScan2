package com.example.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DocumentEntity
import com.example.imaging.ImageProcessingUtils
import com.example.pdf.PdfExportConfig
import com.example.ui.viewmodels.DocuScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ToolsScreen(
    viewModel: DocuScanViewModel,
    onNavigateToDocument: (Long) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val documents by viewModel.documents.collectAsState()

    var showMergeDialog by remember { mutableStateOf(false) }
    var selectedMergeDocs = remember { mutableStateListOf<DocumentEntity>() }
    var isMerging by remember { mutableStateOf(false) }

    var showWatermarkDialog by remember { mutableStateOf(false) }
    var watermarkDoc by remember { mutableStateOf<DocumentEntity?>(null) }
    var watermarkInput by remember { mutableStateOf("CONFIDENCIAL") }
    var isWatermarking by remember { mutableStateOf(false) }

    var showOcrResultDialog by remember { mutableStateOf(false) }
    var extractedOcrText by remember { mutableStateOf("") }
    var isRunningOcr by remember { mutableStateOf(false) }

    val ocrPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isRunningOcr = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val input: InputStream? = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(input)
                    input?.close()
                    if (bitmap != null) {
                        val result = viewModel.ocrManager.recognizeTextFromBitmap(bitmap)
                        withContext(Dispatchers.Main) {
                            extractedOcrText = if (result.fullText.isNotBlank()) result.fullText else "No se detectó texto en la imagen seleccionada."
                            isRunningOcr = false
                            showOcrResultDialog = true
                        }
                    } else {
                        withContext(Dispatchers.Main) { isRunningOcr = false }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { isRunningOcr = false }
                }
            }
        }
    }

    // Merge Dialog
    if (showMergeDialog) {
        AlertDialog(
            onDismissRequest = { if (!isMerging) showMergeDialog = false },
            title = { Text("Unir Documentos PDF") },
            text = {
                Column {
                    Text("Selecciona 2 o más documentos para combinarlos en un único PDF:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyColumn(modifier = Modifier.height(240.dp)) {
                        items(documents) { doc ->
                            val isSelected = selectedMergeDocs.contains(doc)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        if (isSelected) selectedMergeDocs.remove(doc)
                                        else selectedMergeDocs.add(doc)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = doc.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${doc.pageCount} p.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedMergeDocs.size >= 2) {
                            isMerging = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val pdfFiles = selectedMergeDocs.mapNotNull { it.pdfPath?.let { p -> File(p) } }
                                val mergedTitle = "DocuScan_Unido_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"
                                val mergedFile = viewModel.pdfManager.mergePdfFiles(pdfFiles, mergedTitle)

                                withContext(Dispatchers.Main) {
                                    isMerging = false
                                    showMergeDialog = false
                                    selectedMergeDocs.clear()
                                    viewModel.pdfManager.sharePdf(mergedFile)
                                }
                            }
                        } else {
                            Toast.makeText(context, "Selecciona al menos 2 documentos", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isMerging && selectedMergeDocs.size >= 2
                ) {
                    if (isMerging) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    else Text("Unir y Compartir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }, enabled = !isMerging) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Watermark Dialog
    if (showWatermarkDialog && watermarkDoc != null) {
        AlertDialog(
            onDismissRequest = { if (!isWatermarking) showWatermarkDialog = false },
            title = { Text("Añadir Marca de Agua") },
            text = {
                Column {
                    Text("Texto visible en diagonal a lo largo de cada página del PDF:")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = watermarkInput,
                        onValueChange = { watermarkInput = it },
                        label = { Text("Texto de la marca de agua") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isWatermarking = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val pages = viewModel.repository.getPagesList(watermarkDoc!!.id)
                            val watermarkedTitle = "${watermarkDoc!!.title}_MarcaAgua"
                            val watermarkedFile = viewModel.pdfManager.createPdfFromPages(
                                pages = pages,
                                config = PdfExportConfig(
                                    title = watermarkedTitle,
                                    pageSize = viewModel.userPreferences.value.defaultPageSize,
                                    watermarkText = watermarkInput.trim()
                                )
                            )
                            withContext(Dispatchers.Main) {
                                isWatermarking = false
                                showWatermarkDialog = false
                                viewModel.pdfManager.sharePdf(watermarkedFile)
                            }
                        }
                    },
                    enabled = !isWatermarking && watermarkInput.isNotBlank()
                ) {
                    if (isWatermarking) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    else Text("Generar PDF con Marca")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkDialog = false }, enabled = !isWatermarking) {
                    Text("Cancelar")
                }
            }
        )
    }

    // OCR Result Dialog
    if (showOcrResultDialog) {
        AlertDialog(
            onDismissRequest = { showOcrResultDialog = false },
            title = { Text("Texto Extraído (OCR Local)") },
            text = {
                Column(modifier = Modifier.height(260.dp)) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        LazyColumn(modifier = Modifier.padding(12.dp)) {
                            item {
                                Text(
                                    text = extractedOcrText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.ocrManager.copyToClipboard(extractedOcrText)
                    showOcrResultDialog = false
                }) {
                    Text("Copiar al Portapapeles")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.ocrManager.shareText(extractedOcrText)
                    showOcrResultDialog = false
                }) {
                    Text("Compartir")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Herramientas Documentales",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Utilidades avanzadas de PDF y procesamiento local",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tool 1: Merge PDFs
        item {
            ToolCard(
                title = "Unir Documentos PDF",
                description = "Combina múltiples documentos escaneados en un único archivo PDF continuo y ordenado.",
                icon = Icons.Default.MergeType,
                iconBg = Color(0xFF0C4A60),
                onClick = {
                    selectedMergeDocs.clear()
                    showMergeDialog = true
                }
            )
        }

        // Tool 2: OCR on any image
        item {
            ToolCard(
                title = "Reconocimiento Óptico (OCR)",
                description = "Selecciona cualquier foto o captura de tu galería para extraer texto en español al instante.",
                icon = Icons.Default.TextFields,
                iconBg = Color(0xFF009688),
                onClick = { ocrPhotoLauncher.launch("image/*") }
            )
        }

        // Tool 3: Watermark
        item {
            ToolCard(
                title = "Añadir Marca de Agua",
                description = "Añade texto personalizado translúcido en diagonal como «CONFIDENCIAL» o «COPIA».",
                icon = Icons.Default.WaterDrop,
                iconBg = Color(0xFF028090),
                onClick = {
                    if (documents.isNotEmpty()) {
                        watermarkDoc = documents.first()
                        showWatermarkDialog = true
                    } else {
                        Toast.makeText(context, "Primero debes escanear o tener un documento", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // Tool 4: Export JPG
        item {
            ToolCard(
                title = "Compartir como Imágenes JPG",
                description = "Exporta las páginas escaneadas como fotografías de alta resolución en formato JPG.",
                icon = Icons.Default.Description,
                iconBg = Color(0xFF1B4965),
                onClick = {
                    val firstDoc = documents.firstOrNull()
                    if (firstDoc != null) {
                        coroutineScope.launch {
                            val pages = viewModel.repository.getPagesList(firstDoc.id)
                            val paths = pages.map { it.processedImagePath }
                            viewModel.pdfManager.shareImages(paths)
                        }
                    } else {
                        Toast.makeText(context, "No hay documentos disponibles", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@Composable
fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconBg: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
