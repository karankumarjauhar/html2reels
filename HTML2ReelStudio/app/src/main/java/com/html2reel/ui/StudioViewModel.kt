package com.html2reel.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.html2reel.Html2ReelApp
import com.html2reel.data.MediaStoreExporter
import com.html2reel.data.UriFileHelper
import com.html2reel.model.AppScreen
import com.html2reel.model.AspectPreset
import com.html2reel.model.AudioConfig
import com.html2reel.model.RecentProject
import com.html2reel.model.RenderConfig
import com.html2reel.model.RenderState
import com.html2reel.render.VirtualWebViewHost
import com.html2reel.service.RenderForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StudioUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val activeConfig: RenderConfig? = null,
    val previewHtml: String = "",
    val hasExplicitSeekHook: Boolean = false,
    val snackbarMessage: String? = null,
    val gallerySavedUri: Uri? = null,
    val isSavingToGallery: Boolean = false
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as Html2ReelApp
    private val container = app.container

    val recentProjects: StateFlow<List<RecentProject>> = container.recentProjectsStore.recentsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val renderState: StateFlow<RenderState> = container.renderState

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            renderState.collect { state ->
                when (state) {
                    is RenderState.Rendering -> {
                        if (_uiState.value.currentScreen != AppScreen.RENDERING) {
                            _uiState.update { it.copy(currentScreen = AppScreen.RENDERING) }
                        }
                    }
                    is RenderState.Completed -> {
                        _uiState.update {
                            it.copy(
                                currentScreen = AppScreen.EXPORT_COMPLETE,
                                gallerySavedUri = null
                            )
                        }
                    }
                    is RenderState.Cancelled -> {
                        if (_uiState.value.currentScreen == AppScreen.RENDERING) {
                            _uiState.update {
                                it.copy(
                                    currentScreen = AppScreen.STUDIO,
                                    snackbarMessage = "Render cancelled"
                                )
                            }
                        }
                    }
                    is RenderState.Failed -> {
                        _uiState.update {
                            it.copy(
                                currentScreen = AppScreen.STUDIO,
                                snackbarMessage = "Export failed: ${state.message}"
                            )
                        }
                    }
                    RenderState.Idle -> Unit
                }
            }
        }
    }

    fun importHtmlFromUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val html = UriFileHelper.readTextFromUri(app, uri)
                    val fallbackName = UriFileHelper.resolveDisplayName(app, uri)
                        .substringBeforeLast('.')
                    html to fallbackName
                }
            }.onSuccess { (html, name) ->
                openRawHtml(html, fallbackTitle = name)
            }.onFailure { err ->
                postMessage("Failed to import HTML: ${err.message}")
            }
        }
    }

    fun openSampleAsset(assetFileName: String, displayName: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.assets.open("samples/$assetFileName").bufferedReader(Charsets.UTF_8).readText()
                }
            }.onSuccess { html ->
                openRawHtml(html, fallbackTitle = displayName)
            }.onFailure { err ->
                postMessage("Failed to load sample: ${err.message}")
            }
        }
    }

    fun openRecentProject(project: RecentProject) {
        viewModelScope.launch {
            runCatching {
                container.recentProjectsStore.loadHtml(project)
            }.onSuccess { html ->
                openRawHtml(html, fallbackTitle = project.title, existingId = project.id)
            }.onFailure { err ->
                postMessage("Could not open project: ${err.message}")
            }
        }
    }

    fun deleteRecentProject(projectId: String) {
        viewModelScope.launch {
            container.recentProjectsStore.removeProject(projectId)
        }
    }

    fun openRawHtml(
        rawHtml: String,
        fallbackTitle: String = "Untitled Reel",
        existingId: String? = null
    ) {
        if (rawHtml.isBlank()) {
            postMessage("HTML content is empty")
            return
        }
        viewModelScope.launch {
            val preprocessed = withContext(Dispatchers.Default) {
                container.rustCore.preprocessHtml(
                    rawHtml = rawHtml,
                    exportMode = false,
                    autoHideHud = false,
                    fontBaseUrl = VirtualWebViewHost.FONT_BASE_URL
                )
            }
            val meta = preprocessed.metadata
            val preset = matchPreset(meta.width?.toInt(), meta.height?.toInt())
            val duration = (meta.durationSec?.toFloat() ?: 10f).coerceIn(1f, 600f)
            val fps = when (meta.fps?.toInt()) {
                24 -> 24
                60 -> 60
                else -> 30
            }
            val title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val id = existingId ?: UUID.randomUUID().toString()

            val config = RenderConfig(
                projectId = id,
                projectTitle = title,
                rawHtml = rawHtml,
                preset = preset,
                fps = fps,
                durationSec = duration
            )

            container.recentProjectsStore.saveProject(
                existingId = id,
                title = title,
                rawHtml = rawHtml,
                durationSec = duration,
                resolutionLabel = "${preset.width}×${preset.height}"
            )

            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.STUDIO,
                    activeConfig = config,
                    previewHtml = preprocessed.html,
                    hasExplicitSeekHook = meta.hasExplicitSeekHook
                )
            }
        }
    }

    fun updateConfig(transform: (RenderConfig) -> RenderConfig) {
        _uiState.update { state ->
            val current = state.activeConfig ?: return@update state
            state.copy(activeConfig = transform(current))
        }
    }

    fun updateAudio(transform: (AudioConfig) -> AudioConfig) {
        updateConfig { cfg -> cfg.copy(audio = transform(cfg.audio)) }
    }

    fun pickVoiceover(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { UriFileHelper.resolveDisplayName(app, uri) }
            updateAudio { it.copy(voiceoverUri = uri.toString(), voiceoverName = name) }
        }
    }

    fun pickBgm(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { UriFileHelper.resolveDisplayName(app, uri) }
            updateAudio { it.copy(bgmUri = uri.toString(), bgmName = name) }
        }
    }

    fun startExport() {
        val config = _uiState.value.activeConfig ?: return
        viewModelScope.launch {
            container.recentProjectsStore.saveProject(
                existingId = config.projectId,
                title = config.projectTitle,
                rawHtml = config.rawHtml,
                durationSec = config.durationSec,
                resolutionLabel = "${config.preset.width}×${config.preset.height}"
            )
            container.pendingRenderConfig = config
            container.updateRenderState(
                RenderState.Rendering(
                    currentFrame = 0,
                    totalFrames = config.totalFrames,
                    elapsedMs = 0L,
                    etaMs = 0L,
                    phaseLabel = "Starting render service..."
                )
            )
            _uiState.update { it.copy(currentScreen = AppScreen.RENDERING, gallerySavedUri = null) }
            RenderForegroundService.start(app)
        }
    }

    fun cancelExport() {
        RenderForegroundService.cancel(app)
    }

    fun saveToGallery(outputPath: String) {
        val title = _uiState.value.activeConfig?.projectTitle ?: "HTML2Reel"
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingToGallery = true) }
            runCatching {
                MediaStoreExporter.saveToGallery(app, File(outputPath), title)
            }.onSuccess { uri ->
                _uiState.update {
                    it.copy(
                        isSavingToGallery = false,
                        gallerySavedUri = uri,
                        snackbarMessage = "Saved to Movies/HTML2Reel"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSavingToGallery = false,
                        snackbarMessage = "Save failed: ${err.message}"
                    )
                }
            }
        }
    }

    fun navigateTo(screen: AppScreen) {
        if (screen != AppScreen.RENDERING) {
            container.updateRenderState(RenderState.Idle)
        }
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun postMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun matchPreset(w: Int?, h: Int?): AspectPreset {
        if (w == null || h == null) return AspectPreset.REEL_FHD
        return AspectPreset.entries.firstOrNull { it.width == w && it.height == h }
            ?: when {
                w > h -> AspectPreset.LANDSCAPE_FHD
                w == h -> AspectPreset.SQUARE_FHD
                w <= 720 -> AspectPreset.REEL_HD
                else -> AspectPreset.REEL_FHD
            }
    }
}
