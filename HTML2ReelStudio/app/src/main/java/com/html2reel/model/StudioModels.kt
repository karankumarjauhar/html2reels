package com.html2reel.model

import android.graphics.Bitmap
import kotlin.math.roundToInt

enum class AspectPreset(
    val label: String,
    val shortLabel: String,
    val width: Int,
    val height: Int
) {
    REEL_HD("720×1280 (9:16 HD)", "9:16 HD", 720, 1280),
    REEL_FHD("1080×1920 (9:16 FHD)", "9:16 FHD", 1080, 1920),
    LANDSCAPE_FHD("1920×1080 (16:9 FHD)", "16:9", 1920, 1080),
    SQUARE_FHD("1080×1080 (1:1 Square)", "1:1", 1080, 1080);

    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()
}

data class AudioConfig(
    val voiceoverUri: String? = null,
    val voiceoverName: String? = null,
    val bgmUri: String? = null,
    val bgmName: String? = null,
    val voiceVolume: Float = 1.0f,
    val bgmVolume: Float = 0.65f,
    val autoDuckBgm: Boolean = true,
    val fadeInSec: Float = 0.5f,
    val fadeOutSec: Float = 1.0f
) {
    val hasAnyTrack: Boolean
        get() = !voiceoverUri.isNullOrBlank() || !bgmUri.isNullOrBlank()
}

data class RenderConfig(
    val projectId: String,
    val projectTitle: String,
    val rawHtml: String,
    val preset: AspectPreset = AspectPreset.REEL_FHD,
    val fps: Int = 30,
    val durationSec: Float = 10f,
    val bitrateMbps: Int = 10,
    val autoHideHud: Boolean = true,
    val audio: AudioConfig = AudioConfig()
) {
    val totalFrames: Int
        get() = (durationSec * fps).roundToInt().coerceAtLeast(1)

    val bitrateBps: Int
        get() = bitrateMbps.coerceIn(6, 16) * 1_000_000
}

data class RecentProject(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val filePath: String,
    val durationSec: Float,
    val resolutionLabel: String
)

sealed interface RenderState {
    data object Idle : RenderState

    data class Rendering(
        val currentFrame: Int,
        val totalFrames: Int,
        val elapsedMs: Long,
        val etaMs: Long,
        val phaseLabel: String,
        val previewThumbnail: Bitmap? = null
    ) : RenderState {
        val progress: Float
            get() = if (totalFrames <= 0) 0f else (currentFrame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
    }

    data class Completed(
        val outputPath: String,
        val durationMs: Long,
        val fileSizeBytes: Long,
        val videoFrames: Long,
        val isPartial: Boolean = false,
        val warningMessage: String? = null
    ) : RenderState

    data class Failed(
        val message: String,
        val partialOutputPath: String? = null
    ) : RenderState

    data object Cancelled : RenderState
}

enum class AppScreen {
    HOME,
    STUDIO,
    RENDERING,
    EXPORT_COMPLETE
}
