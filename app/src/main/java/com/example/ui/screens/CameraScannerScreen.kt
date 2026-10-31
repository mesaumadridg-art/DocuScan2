package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.imaging.QuadCorners
import com.example.ui.viewmodels.DraftPage
import com.example.ui.viewmodels.ScannerViewModel

@Composable
fun CameraScannerScreen(
    scannerViewModel: ScannerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scannerViewModel.importImagesFromUris(uris) {
                onNavigateToEditor()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val draftPages by scannerViewModel.draftPages.collectAsState()
    val flashMode by scannerViewModel.flashMode.collectAsState()
    val isBackCamera by scannerViewModel.isBackCamera.collectAsState()
    val isBatchMode by scannerViewModel.isBatchMode.collectAsState()
    val isProcessing by scannerViewModel.isProcessingCapture.collectAsState()

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()
    }

    LaunchedEffect(flashMode) {
        imageCapture.flashMode = flashMode
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1419))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color(0xFF00B4D8),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Permiso de Cámara Requerido",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DocuScan necesita acceso a tu cámara para capturar y digitalizar documentos con detección de bordes y perspectiva en tiempo real.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0C4CE),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6B85), contentColor = Color.White),
                    modifier = Modifier.testTag("grant_camera_permission_button")
                ) {
                    Text("Conceder Permiso", color = Color.White)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2E38), contentColor = Color(0xFFE3F2F7))
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFF64DFDF))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("O importar desde galería", color = Color.White)
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // CameraX Viewfinder
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val cameraSelector = if (isBackCamera) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            ctx as androidx.lifecycle.LifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (exc: Exception) {
                        exc.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            update = { previewView ->
                // Trigger re-bind if camera flipped
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val cameraSelector = if (isBackCamera) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            context as androidx.lifecycle.LifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Viewfinder Guide Overlay (Rectangular document frame hint)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 120.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                // Subtle corner highlights
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(24.dp)
                        .border(3.dp, Color(0xFF64DFDF), RoundedCornerShape(topStart = 16.dp))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .border(3.dp, Color(0xFF64DFDF), RoundedCornerShape(topEnd = 16.dp))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(24.dp)
                        .border(3.dp, Color(0xFF64DFDF), RoundedCornerShape(bottomStart = 16.dp))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .border(3.dp, Color(0xFF64DFDF), RoundedCornerShape(bottomEnd = 16.dp))
                )
            }
        }

        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .background(Color(0x66000000), CircleShape)
                    .size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White
                )
            }

            // Mode Selector: Página única vs Multipágina
            Row(
                modifier = Modifier
                    .background(Color(0x88000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !isBatchMode,
                    onClick = { scannerViewModel.setBatchMode(false) },
                    label = { Text("Única", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF0C4A60),
                        selectedLabelColor = Color.White
                    ),
                    border = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = isBatchMode,
                    onClick = { scannerViewModel.setBatchMode(true) },
                    label = { Text("Multipágina", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF009688),
                        selectedLabelColor = Color.White
                    ),
                    border = null
                )
            }

            Row {
                // Flash toggle
                IconButton(
                    onClick = { scannerViewModel.toggleFlash() },
                    modifier = Modifier
                        .background(Color(0x66000000), CircleShape)
                        .size(44.dp)
                ) {
                    val flashIcon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOff
                    }
                    Icon(flashIcon, contentDescription = "Flash", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Camera switch
                IconButton(
                    onClick = { scannerViewModel.switchCamera() },
                    modifier = Modifier
                        .background(Color(0x66000000), CircleShape)
                        .size(44.dp)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Cambiar cámara", tint = Color.White)
                }
            }
        }

        // Bottom Controls Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC0B1419))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Document guidance hint
            Text(
                text = if (isBatchMode) "Modo multipágina: añade varias hojas seguidas" else "Alinea el documento dentro del marco",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF90E0EF),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Prominent banner showing pages captured and direct button to save/edit
            AnimatedVisibility(visible = draftPages.isNotEmpty()) {
                Surface(
                    color = Color(0xFF009688),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .clickable(onClick = onNavigateToEditor)
                        .testTag("banner_to_editor_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${draftPages.size} ${if (draftPages.size == 1) "página capturada" else "páginas capturadas"}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            text = "Guardar / Editar ➔",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery import button
                IconButton(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color(0x44FFFFFF), CircleShape)
                        .testTag("import_gallery_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Importar fotos",
                        tint = Color.White
                    )
                }

                // Shutter Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .border(4.dp, Color(0xFF64DFDF), CircleShape)
                        .padding(6.dp)
                        .background(Color.White, CircleShape)
                        .clickable(enabled = !isProcessing) {
                            val executor = ContextCompat.getMainExecutor(context)
                            imageCapture.takePicture(
                                executor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                                        scannerViewModel.processCapturedImageProxy(image) {
                                            if (!isBatchMode) {
                                                onNavigateToEditor()
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        exception.printStackTrace()
                                        android.widget.Toast.makeText(
                                            context,
                                            "Error al capturar: ${exception.localizedMessage ?: "Cámara no disponible"}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                        .testTag("camera_shutter_button")
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = Color(0xFF0C4A60),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .background(Color(0xFF0C4A60), CircleShape)
                        )
                    }
                }

                // Finish / Review button
                if (draftPages.isNotEmpty()) {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = Color(0xFF00B4D8)) {
                                Text(draftPages.size.toString(), color = Color.White)
                            }
                        }
                    ) {
                        IconButton(
                            onClick = onNavigateToEditor,
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color(0xFF009688), CircleShape)
                                .testTag("finish_batch_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Listo / Editar",
                                tint = Color.White
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.size(52.dp))
                }
            }
        }
    }
}
