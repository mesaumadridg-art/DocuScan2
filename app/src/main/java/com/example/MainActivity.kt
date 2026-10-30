package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ThemeMode
import com.example.ui.screens.CameraScannerScreen
import com.example.ui.screens.DocumentCropAndFilterScreen
import com.example.ui.screens.DocumentViewerScreen
import com.example.ui.screens.DocumentsLibraryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.DocuScanTheme
import com.example.ui.viewmodels.DocuScanViewModel
import com.example.ui.viewmodels.EditorViewModel
import com.example.ui.viewmodels.ScannerViewModel

sealed class AppDestination {
    data object Main : AppDestination()
    data object Camera : AppDestination()
    data object Editor : AppDestination()
    data class Viewer(val documentId: Long) : AppDestination()
}

enum class MainNavTab(val label: String) {
    HOME("Inicio"),
    DOCS("Documentos"),
    TOOLS("Herramientas"),
    SETTINGS("Ajustes")
}

class MainActivity : ComponentActivity() {

    private val docuScanViewModel: DocuScanViewModel by viewModels()
    private val scannerViewModel: ScannerViewModel by viewModels()
    private val editorViewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val userPrefs by docuScanViewModel.userPreferences.collectAsState()
            val isDarkTheme = when (userPrefs.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            DocuScanTheme(darkTheme = isDarkTheme) {
                var currentDestination by remember { mutableStateOf<AppDestination>(AppDestination.Main) }
                var currentTab by remember { mutableStateOf(MainNavTab.HOME) }

                AnimatedContent(
                    targetState = currentDestination,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "AppNavigation"
                ) { destination ->
                    when (destination) {
                        is AppDestination.Main -> {
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                bottomBar = {
                                    NavigationBar(
                                        containerColor = if (isDarkTheme) Color(0xFF131D24) else Color.White,
                                        tonalElevation = 6.dp
                                    ) {
                                        // Tab 1: Inicio
                                        NavigationBarItem(
                                            selected = currentTab == MainNavTab.HOME,
                                            onClick = { currentTab = MainNavTab.HOME },
                                            icon = {
                                                Icon(
                                                    if (currentTab == MainNavTab.HOME) Icons.Default.Home else Icons.Outlined.Home,
                                                    contentDescription = "Inicio"
                                                )
                                            },
                                            label = { Text("Inicio", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = Color(0xFF0C4A60).copy(alpha = 0.2f),
                                                selectedIconColor = Color(0xFF0C4A60)
                                            ),
                                            modifier = Modifier.testTag("nav_tab_home")
                                        )

                                        // Tab 2: Documentos
                                        NavigationBarItem(
                                            selected = currentTab == MainNavTab.DOCS,
                                            onClick = { currentTab = MainNavTab.DOCS },
                                            icon = {
                                                Icon(
                                                    if (currentTab == MainNavTab.DOCS) Icons.Default.Folder else Icons.Outlined.Folder,
                                                    contentDescription = "Documentos"
                                                )
                                            },
                                            label = { Text("Docs", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = Color(0xFF0C4A60).copy(alpha = 0.2f),
                                                selectedIconColor = Color(0xFF0C4A60)
                                            ),
                                            modifier = Modifier.testTag("nav_tab_docs")
                                        )

                                        // Tab 3: Herramientas
                                        NavigationBarItem(
                                            selected = currentTab == MainNavTab.TOOLS,
                                            onClick = { currentTab = MainNavTab.TOOLS },
                                            icon = {
                                                Icon(
                                                    if (currentTab == MainNavTab.TOOLS) Icons.Default.Build else Icons.Outlined.Build,
                                                    contentDescription = "Herramientas"
                                                )
                                            },
                                            label = { Text("Herramientas", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = Color(0xFF0C4A60).copy(alpha = 0.2f),
                                                selectedIconColor = Color(0xFF0C4A60)
                                            ),
                                            modifier = Modifier.testTag("nav_tab_tools")
                                        )

                                        // Tab 4: Ajustes
                                        NavigationBarItem(
                                            selected = currentTab == MainNavTab.SETTINGS,
                                            onClick = { currentTab = MainNavTab.SETTINGS },
                                            icon = {
                                                Icon(
                                                    if (currentTab == MainNavTab.SETTINGS) Icons.Default.Settings else Icons.Outlined.Settings,
                                                    contentDescription = "Ajustes"
                                                )
                                            },
                                            label = { Text("Ajustes", fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = Color(0xFF0C4A60).copy(alpha = 0.2f),
                                                selectedIconColor = Color(0xFF0C4A60)
                                            ),
                                            modifier = Modifier.testTag("nav_tab_settings")
                                        )
                                    }
                                },
                                floatingActionButton = {
                                    FloatingActionButton(
                                        onClick = {
                                            scannerViewModel.clearDrafts()
                                            currentDestination = AppDestination.Camera
                                        },
                                        containerColor = Color(0xFF0C4A60),
                                        contentColor = Color.White,
                                        shape = CircleShape,
                                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                                        modifier = Modifier.testTag("main_scan_fab")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = "Escanear documento",
                                            tint = Color(0xFF64DFDF),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                },
                                floatingActionButtonPosition = FabPosition.End
                            ) { innerPadding ->
                                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                    when (currentTab) {
                                        MainNavTab.HOME -> HomeScreen(
                                            viewModel = docuScanViewModel,
                                            onNavigateToScanner = {
                                                scannerViewModel.clearDrafts()
                                                currentDestination = AppDestination.Camera
                                            },
                                            onNavigateToDocument = { docId ->
                                                currentDestination = AppDestination.Viewer(docId)
                                            },
                                            onNavigateToTools = {
                                                currentTab = MainNavTab.TOOLS
                                            },
                                            onImportPhotos = { uris ->
                                                scannerViewModel.clearDrafts()
                                                scannerViewModel.importImagesFromUris(uris) {
                                                    val drafts = scannerViewModel.draftPages.value
                                                    if (drafts.isNotEmpty()) {
                                                        editorViewModel.initDraftPages(drafts)
                                                        currentDestination = AppDestination.Editor
                                                    }
                                                }
                                            }
                                        )

                                        MainNavTab.DOCS -> DocumentsLibraryScreen(
                                            viewModel = docuScanViewModel,
                                            onNavigateToDocument = { docId ->
                                                currentDestination = AppDestination.Viewer(docId)
                                            }
                                        )

                                        MainNavTab.TOOLS -> ToolsScreen(
                                            viewModel = docuScanViewModel,
                                            onNavigateToDocument = { docId ->
                                                currentDestination = AppDestination.Viewer(docId)
                                            }
                                        )

                                        MainNavTab.SETTINGS -> SettingsScreen(
                                            viewModel = docuScanViewModel
                                        )
                                    }
                                }
                            }
                        }

                        is AppDestination.Camera -> {
                            CameraScannerScreen(
                                scannerViewModel = scannerViewModel,
                                onNavigateBack = { currentDestination = AppDestination.Main },
                                onNavigateToEditor = {
                                    val drafts = scannerViewModel.draftPages.value
                                    if (drafts.isNotEmpty()) {
                                        editorViewModel.initDraftPages(drafts)
                                        currentDestination = AppDestination.Editor
                                    }
                                }
                            )
                        }

                        is AppDestination.Editor -> {
                            DocumentCropAndFilterScreen(
                                editorViewModel = editorViewModel,
                                onNavigateBack = { currentDestination = AppDestination.Camera },
                                onSaveSuccess = { newDocId ->
                                    scannerViewModel.clearDrafts()
                                    currentDestination = AppDestination.Viewer(newDocId)
                                }
                            )
                        }

                        is AppDestination.Viewer -> {
                            DocumentViewerScreen(
                                documentId = destination.documentId,
                                viewModel = docuScanViewModel,
                                onNavigateBack = { currentDestination = AppDestination.Main },
                                onAddPages = { docId ->
                                    scannerViewModel.clearDrafts()
                                    currentDestination = AppDestination.Camera
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
