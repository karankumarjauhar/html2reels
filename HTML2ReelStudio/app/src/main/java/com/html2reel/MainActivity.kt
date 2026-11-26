package com.html2reel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.html2reel.data.MediaStoreExporter
import com.html2reel.model.AppScreen
import com.html2reel.model.RenderState
import com.html2reel.ui.StudioViewModel
import com.html2reel.ui.export.ExportCompleteScreen
import com.html2reel.ui.home.HomeScreen
import com.html2reel.ui.progress.RenderProgressScreen
import com.html2reel.ui.studio.StudioScreen
import com.html2reel.ui.theme.HTML2ReelTheme
import com.html2reel.ui.theme.StudioBg
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HTML2ReelTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val recents by viewModel.recentProjects.collectAsStateWithLifecycle()
                val renderState by viewModel.renderState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                val htmlPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.importHtmlFromUri(it) }
                }

                val voiceoverPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.pickVoiceover(it) }
                }

                val bgmPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.pickBgm(it) }
                }

                val notificationPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    viewModel.startExport()
                }

                LaunchedEffect(uiState.snackbarMessage) {
                    val msg = uiState.snackbarMessage
                    if (msg != null) {
                        snackbarHostState.showSnackbar(msg)
                        viewModel.consumeMessage()
                    }
                }

                BackHandler(enabled = uiState.currentScreen != AppScreen.HOME) {
                    when (uiState.currentScreen) {
                        AppScreen.STUDIO -> viewModel.navigateTo(AppScreen.HOME)
                        AppScreen.EXPORT_COMPLETE -> viewModel.navigateTo(AppScreen.STUDIO)
                        AppScreen.RENDERING -> Unit // User explicitly taps Cancel to stop an active render.
                        AppScreen.HOME -> Unit
                    }
                }

                Scaffold(
                    containerColor = StudioBg,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    when (uiState.currentScreen) {
                        AppScreen.HOME -> {
                            HomeScreen(
                                recentProjects = recents,
                                onImportHtmlClick = {
                                    htmlPicker.launch(arrayOf("text/html", "application/xhtml+xml", "*/*"))
                                },
                                onPasteHtmlSubmit = { title, html ->
                                    viewModel.openRawHtml(html, fallbackTitle = title)
                                },
                                onOpenSample = { asset, title ->
                                    viewModel.openSampleAsset(asset, title)
                                },
                                onOpenRecent = { project ->
                                    viewModel.openRecentProject(project)
                                },
                                onDeleteRecent = { id ->
                                    viewModel.deleteRecentProject(id)
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        AppScreen.STUDIO -> {
                            val cfg = uiState.activeConfig
                            if (cfg != null) {
                                StudioScreen(
                                    config = cfg,
                                    previewHtml = uiState.previewHtml,
                                    hasExplicitSeekHook = uiState.hasExplicitSeekHook,
                                    onBack = { viewModel.navigateTo(AppScreen.HOME) },
                                    onConfigChange = viewModel::updateConfig,
                                    onAudioChange = viewModel::updateAudio,
                                    onPickVoiceover = {
                                        voiceoverPicker.launch(arrayOf("audio/*"))
                                    },
                                    onPickBgm = {
                                        bgmPicker.launch(arrayOf("audio/*"))
                                    },
                                    onExportClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            viewModel.startExport()
                                        }
                                    },
                                    modifier = Modifier.padding(innerPadding)
                                )
                            }
                        }

                        AppScreen.RENDERING -> {
                            val rendering = renderState as? RenderState.Rendering
                                ?: RenderState.Rendering(
                                    currentFrame = 0,
                                    totalFrames = uiState.activeConfig?.totalFrames ?: 1,
                                    elapsedMs = 0L,
                                    etaMs = 0L,
                                    phaseLabel = "Initializing..."
                                )
                            RenderProgressScreen(
                                state = rendering,
                                projectTitle = uiState.activeConfig?.projectTitle ?: "Reel",
                                onCancelClick = { viewModel.cancelExport() },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        AppScreen.EXPORT_COMPLETE -> {
                            val completed = renderState as? RenderState.Completed
                            if (completed != null) {
                                ExportCompleteScreen(
                                    state = completed,
                                    isSavingToGallery = uiState.isSavingToGallery,
                                    gallerySavedUri = uiState.gallerySavedUri,
                                    onSaveToGallery = { viewModel.saveToGallery(completed.outputPath) },
                                    onShareClick = {
                                        val shareIntent = MediaStoreExporter.buildShareIntent(
                                            this@MainActivity,
                                            File(completed.outputPath)
                                        )
                                        startActivity(Intent.createChooser(shareIntent, "Share MP4 Reel"))
                                    },
                                    onBackToStudio = { viewModel.navigateTo(AppScreen.STUDIO) },
                                    modifier = Modifier.padding(innerPadding)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
