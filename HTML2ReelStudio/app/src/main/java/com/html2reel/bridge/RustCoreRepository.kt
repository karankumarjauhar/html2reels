package com.html2reel.bridge

import com.html2reel.model.AudioConfig
import com.html2reel.render.VirtualWebViewHost
import uniffi.html2reel_core.AudioMixConfig
import uniffi.html2reel_core.AudioMixSession
import uniffi.html2reel_core.HtmlMetadata
import uniffi.html2reel_core.Mp4MuxerSession
import uniffi.html2reel_core.PreprocessOptions
import uniffi.html2reel_core.PreprocessedHtml
import uniffi.html2reel_core.extractHtmlMetadata
import uniffi.html2reel_core.preprocessHtmlDocument

/**
 * Clean Kotlin boundary over the UniFFI-generated Rust Native Core.
 */
class RustCoreRepository {

    fun preprocessHtml(
        rawHtml: String,
        exportMode: Boolean,
        autoHideHud: Boolean,
        fontBaseUrl: String = VirtualWebViewHost.FONT_BASE_URL
    ): PreprocessedHtml {
        return preprocessHtmlDocument(
            rawHtml = rawHtml,
            options = PreprocessOptions(
                exportMode = exportMode,
                autoHideHud = autoHideHud,
                injectOfflineFonts = true,
                fontBaseUrl = fontBaseUrl
            )
        )
    }

    fun extractMetadata(rawHtml: String): HtmlMetadata {
        return extractHtmlMetadata(rawHtml)
    }

    fun openAudioMixSession(
        voiceoverPath: String?,
        bgmPath: String?,
        audio: AudioConfig,
        durationSec: Double
    ): AudioMixSession {
        return AudioMixSession(
            AudioMixConfig(
                voiceoverPath = voiceoverPath,
                bgmPath = bgmPath,
                durationSec = durationSec,
                voiceVolume = audio.voiceVolume,
                bgmVolume = audio.bgmVolume,
                autoDuckBgm = audio.autoDuckBgm,
                fadeInSec = audio.fadeInSec.toDouble(),
                fadeOutSec = audio.fadeOutSec.toDouble()
            )
        )
    }

    fun createMuxerSession(
        outputPath: String,
        width: Int,
        height: Int,
        fps: Int,
        hasAudio: Boolean,
        audioSampleRate: Int,
        audioChannels: Int
    ): Mp4MuxerSession {
        return Mp4MuxerSession(
            outputPath = outputPath,
            width = width.toUInt(),
            height = height.toUInt(),
            fps = fps.toUInt(),
            hasAudio = hasAudio,
            audioSampleRate = audioSampleRate.toUInt(),
            audioChannels = audioChannels.toUShort()
        )
    }
}
