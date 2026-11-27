package com.html2reel.render

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.html2reel.bridge.RustCoreRepository
import com.html2reel.data.UriFileHelper
import com.html2reel.model.RenderConfig
import com.html2reel.model.RenderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uniffi.html2reel_core.AudioMixSession
import uniffi.html2reel_core.Mp4MuxerSession
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates deterministic WebView frame stepping, thread-affine GLES blitting,
 * MediaCodec H.264/AAC encoding, and Rust fast-start MP4 muxing with partial-output recovery.
 */
class RenderPipeline(
    private val context: Context,
    private val rustCore: RustCoreRepository
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    suspend fun execute(
        config: RenderConfig,
        onProgress: (RenderState.Rendering) -> Unit
    ): RenderState {
        val startMs = System.currentTimeMillis()
        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outputFile = File(exportsDir, "html2reel_${startMs}.mp4")

        val stagedFiles = mutableListOf<File>()
        var glThread: HandlerThread? = null
        var videoEncoder: VideoEncoder? = null
        var audioEncoder: AudioEncoder? = null
        var blitter: EglSurfaceBlitter? = null
        var webHost: VirtualWebViewHost? = null
        var muxer: Mp4MuxerSession? = null
        var audioSession: AudioMixSession? = null
        var muxedFrames = 0

        try {
            onProgress(
                RenderState.Rendering(
                    currentFrame = 0,
                    totalFrames = config.totalFrames,
                    elapsedMs = 0L,
                    etaMs = 0L,
                    phaseLabel = "Preparing HTML & audio..."
                )
            )

            // 1. Preprocess HTML via Rust
            val preprocessed = withContext(Dispatchers.Default) {
                rustCore.preprocessHtml(
                    rawHtml = config.rawHtml,
                    exportMode = true,
                    autoHideHud = config.autoHideHud,
                    fontBaseUrl = VirtualWebViewHost.FONT_BASE_URL
                )
            }

            // 2. Decode, resample (48kHz stereo), mix & duck audio via Rust if configured
            val hasAudio = config.audio.hasAnyTrack
            if (hasAudio) {
                audioSession = withContext(Dispatchers.IO) {
                    val voiceFile = config.audio.voiceoverUri?.let {
                        UriFileHelper.stageUriToCache(context, it, "vo").also { f -> stagedFiles.add(f) }
                    }
                    val bgmFile = config.audio.bgmUri?.let {
                        UriFileHelper.stageUriToCache(context, it, "bgm").also { f -> stagedFiles.add(f) }
                    }
                    rustCore.openAudioMixSession(
                        voiceoverPath = voiceFile?.absolutePath,
                        bgmPath = bgmFile?.absolutePath,
                        audio = config.audio,
                        durationSec = config.durationSec.toDouble()
                    )
                }
            }

            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw CancellationException("Cancelled before render start")

            // 3. Open Fast-Start Rust MP4 Muxer
            muxer = rustCore.createMuxerSession(
                outputPath = outputFile.absolutePath,
                width = config.preset.width,
                height = config.preset.height,
                fps = config.fps,
                hasAudio = hasAudio,
                audioSampleRate = 48_000,
                audioChannels = 2
            )

            // 4. Initialize VideoEncoder & thread-affine EglSurfaceBlitter on dedicated GL thread
            val thread = HandlerThread("HTML2Reel-GL").apply { start() }
            glThread = thread
            val glHandler = Handler(thread.looper)

            val vEnc = VideoEncoder(
                width = config.preset.width,
                height = config.preset.height,
                fps = config.fps,
                bitrateBps = config.bitrateBps
            )
            videoEncoder = vEnc

            val glBlitter = runOnHandler(glHandler) {
                EglSurfaceBlitter(
                    codecInputSurface = vEnc.inputSurface,
                    width = config.preset.width,
                    height = config.preset.height
                )
            }
            blitter = glBlitter

            // 5. Initialize VirtualWebViewHost at exact output pixel dimensions and verify capture path
            val host = VirtualWebViewHost(
                appContext = context.applicationContext,
                width = config.preset.width,
                height = config.preset.height,
                preprocessedHtml = preprocessed.html,
                oesSurface = glBlitter.inputSurface
            )
            webHost = host
            val presentationAvailable = host.initialize()

            var latestThumb: Bitmap? = null

            // 6. Step frame = 0 .. totalFrames - 1 deterministically off Main thread
            //    so Choreographer/ViewRootImpl on Main thread never stalls waiting on OES fences.
            withContext(Dispatchers.Default) {
                val activeMode = resolveCaptureMode(
                    host = host,
                    glBlitter = glBlitter,
                    glHandler = glHandler,
                    presentationAvailable = presentationAvailable,
                    durationSec = config.durationSec.toDouble()
                )
                Log.i(TAG, "Starting deterministic frame loop (${config.totalFrames} frames, mode=$activeMode)")

                val totalFrames = config.totalFrames
                val fps = config.fps
                for (frame in 0 until totalFrames) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) throw CancellationException("Render cancelled by user")

                    val tSec = frame.toDouble() / fps.toDouble()
                    val ptsNs = (frame.toLong() * 1_000_000_000L) / fps.toLong()
                    val captureThumb = (frame == 0 || frame % 12 == 0 || frame == totalFrames - 1)

                    val thumb: Bitmap? = renderSingleFrame(
                        mode = activeMode,
                        host = host,
                        glBlitter = glBlitter,
                        glHandler = glHandler,
                        vEnc = vEnc,
                        muxer = muxer,
                        tSec = tSec,
                        ptsNs = ptsNs,
                        seq = frame,
                        captureThumb = captureThumb
                    )
                    if (thumb != null && !thumb.isRecycled) {
                        latestThumb = thumb
                    }
                    muxedFrames = frame + 1

                    val elapsed = System.currentTimeMillis() - startMs
                    val avgPerFrame = elapsed.toDouble() / muxedFrames.toDouble()
                    val eta = ((totalFrames - muxedFrames) * avgPerFrame).toLong().coerceAtLeast(0L)
                    onProgress(
                        RenderState.Rendering(
                            currentFrame = muxedFrames,
                            totalFrames = totalFrames,
                            elapsedMs = elapsed,
                            etaMs = eta,
                            phaseLabel = "Rendering frames",
                            previewThumbnail = latestThumb
                        )
                    )
                }

                // 7. Flush VideoEncoder EOS
                runOnHandler(glHandler) {
                    vEnc.drain(endOfStream = true, muxer = muxer)
                }
            }

            // 8. Hardware AAC encode mixed audio stream if present
            if (hasAudio && audioSession != null) {
                onProgress(
                    RenderState.Rendering(
                        currentFrame = totalFrames,
                        totalFrames = totalFrames,
                        elapsedMs = System.currentTimeMillis() - startMs,
                        etaMs = 500L,
                        phaseLabel = "Encoding AAC audio...",
                        previewThumbnail = null
                    )
                )
                val aEnc = AudioEncoder(sampleRate = 48_000, channels = 2, bitrate = 192_000)
                audioEncoder = aEnc
                withContext(Dispatchers.IO) {
                    aEnc.encodeSession(audioSession, muxer) { cancelled.get() }
                }
            }

            // 9. Finalize Fast-Start MP4 (moov before mdat)
            onProgress(
                RenderState.Rendering(
                    currentFrame = totalFrames,
                    totalFrames = totalFrames,
                    elapsedMs = System.currentTimeMillis() - startMs,
                    etaMs = 0L,
                    phaseLabel = "Writing fast-start MP4...",
                    previewThumbnail = null
                )
            )
            val summary = withContext(Dispatchers.IO) { muxer.finish() }
            return RenderState.Completed(
                outputPath = summary.outputPath,
                durationMs = summary.durationMs.toLong(),
                fileSizeBytes = summary.fileSizeBytes.toLong(),
                videoFrames = summary.videoFrames.toLong(),
                isPartial = false
            )
        } catch (ce: CancellationException) {
            runCatching { muxer?.abort() }
            runCatching { outputFile.delete() }
            return RenderState.Cancelled
        } catch (t: Throwable) {
            // Partial Output Recovery if at least 1 frame was already written to mdat.
            if (muxedFrames > 0 && muxer != null && !cancelled.get()) {
                val partialSummary = runCatching {
                    val thread = glThread
                    if (thread != null && thread.isAlive && videoEncoder != null) {
                        runOnHandler(Handler(thread.looper)) {
                            videoEncoder!!.drain(endOfStream = true, muxer = muxer)
                        }
                    }
                    if (audioSession != null) {
                        val partialAudioSamples = ((muxedFrames.toLong() * 48_000L) / config.fps.toLong())
                        val aEnc = AudioEncoder(sampleRate = 48_000, channels = 2)
                        audioEncoder = aEnc
                        withContext(Dispatchers.IO) {
                            aEnc.encodeSession(audioSession, muxer, partialAudioSamples) { false }
                        }
                    }
                    withContext(Dispatchers.IO) { muxer.finish() }
                }.getOrNull()

                if (partialSummary != null) {
                    return RenderState.Completed(
                        outputPath = partialSummary.outputPath,
                        durationMs = partialSummary.durationMs.toLong(),
                        fileSizeBytes = partialSummary.fileSizeBytes.toLong(),
                        videoFrames = partialSummary.videoFrames.toLong(),
                        isPartial = true,
                        warningMessage = "Render interrupted at frame $muxedFrames/${config.totalFrames} (${t.message}). Partial video saved."
                    )
                }
            }
            runCatching { muxer?.abort() }
            runCatching { outputFile.delete() }
            return RenderState.Failed(message = t.message ?: "Unknown rendering failure")
        } finally {
            withContext(NonCancellable) {
                runCatching { webHost?.release() }
                val thread = glThread
                if (thread != null && thread.isAlive) {
                    runCatching {
                        runOnHandler(Handler(thread.looper)) {
                            blitter?.release()
                        }
                    }
                    thread.quitSafely()
                } else {
                    runCatching { blitter?.release() }
                }
                runCatching { videoEncoder?.release() }
                runCatching { audioEncoder?.release() }
                stagedFiles.forEach { runCatching { it.delete() } }
            }
        }
    }

    private suspend fun resolveCaptureMode(
        host: VirtualWebViewHost,
        glBlitter: EglSurfaceBlitter,
        glHandler: Handler,
        presentationAvailable: Boolean,
        durationSec: Double
    ): CaptureMode {
        if (!presentationAvailable) {
            return CaptureMode.SOFTWARE_BITMAP
        }

        // Probe frame t = 0.0 on VirtualDisplay OES first.
        val seqBefore0 = glBlitter.currentOesSeq()
        host.seekAndWaitForSettle(0.0, -100)
        host.invalidateWebView()
        glBlitter.awaitNewOesFrame(seqBefore0, 220L)

        val oesThumb0 = runOnHandler(glHandler) { glBlitter.peekOesThumbnail() }
        val oesOk0 = oesThumb0 != null && EglSurfaceBlitter.hasVisibleContent(oesThumb0)
        oesThumb0?.recycle()
        if (oesOk0 && glBlitter.currentOesSeq() > 0) {
            return CaptureMode.OES_TEXTURE
        }

        // OES was black/missing at t = 0: check Presentation Window via PixelCopy directly.
        val pc0 = host.capturePixelCopyBitmap()
        if (pc0 != null && EglSurfaceBlitter.hasVisibleContent(pc0)) {
            Log.w(TAG, "OES VirtualDisplay produced black at t=0; switching to PixelCopy")
            return CaptureMode.PIXEL_COPY
        }

        // Both OES and PixelCopy are black at t=0 (either genuine black intro or broken HW Presentation).
        val midT = (durationSec * 0.25).coerceIn(0.2, 3.0)
        val seqMid = glBlitter.currentOesSeq()
        host.seekAndWaitForSettle(midT, -101)
        host.invalidateWebView()
        glBlitter.awaitNewOesFrame(seqMid, 220L)

        val oesThumbMid = runOnHandler(glHandler) { glBlitter.peekOesThumbnail() }
        val oesOkMid = oesThumbMid != null && EglSurfaceBlitter.hasVisibleContent(oesThumbMid)
        oesThumbMid?.recycle()
        if (oesOkMid && glBlitter.currentOesSeq() > 0) {
            host.reloadCleanFromStart(softwareLayer = false)
            return CaptureMode.OES_TEXTURE
        }

        val pcMid = host.capturePixelCopyBitmap()
        if (pcMid != null && EglSurfaceBlitter.hasVisibleContent(pcMid)) {
            Log.w(TAG, "OES VirtualDisplay produced black at midT; switching to PixelCopy")
            host.reloadCleanFromStart(softwareLayer = false)
            return CaptureMode.PIXEL_COPY
        }

        Log.w(TAG, "HW Presentation & PixelCopy both yielded black; switching to LAYER_TYPE_SOFTWARE")
        host.switchToSoftwareFallback()
        return CaptureMode.SOFTWARE_BITMAP
    }

    private suspend fun renderSingleFrame(
        mode: CaptureMode,
        host: VirtualWebViewHost,
        glBlitter: EglSurfaceBlitter,
        glHandler: Handler,
        vEnc: VideoEncoder,
        muxer: Mp4MuxerSession,
        tSec: Double,
        ptsNs: Long,
        seq: Int,
        captureThumb: Boolean
    ): Bitmap? {
        val prevOesSeq = glBlitter.currentOesSeq()
        host.seekAndWaitForSettle(tSec, seq)

        return when (mode) {
            CaptureMode.OES_TEXTURE -> {
                host.invalidateWebView()
                glBlitter.awaitNewOesFrame(prevOesSeq, 45L)
                runOnHandler(glHandler) {
                    val bmp = glBlitter.drawOesFrame(ptsNs, captureThumb)
                    vEnc.drain(endOfStream = false, muxer = muxer)
                    bmp
                }
            }

            CaptureMode.PIXEL_COPY -> {
                val frameBmp = host.capturePixelCopyBitmap() ?: host.drawFallbackBitmap()
                runOnHandler(glHandler) {
                    val bmp = glBlitter.drawBitmapFrame(frameBmp, ptsNs, captureThumb)
                    vEnc.drain(endOfStream = false, muxer = muxer)
                    bmp
                }
            }

            CaptureMode.SOFTWARE_BITMAP -> {
                val frameBmp = host.drawFallbackBitmap()
                runOnHandler(glHandler) {
                    val bmp = glBlitter.drawBitmapFrame(frameBmp, ptsNs, captureThumb)
                    vEnc.drain(endOfStream = false, muxer = muxer)
                    bmp
                }
            }
        }
    }

    private suspend fun <T> runOnHandler(handler: Handler, block: () -> T): T {
        val deferred = CompletableDeferred<T>()
        if (!handler.post {
                try {
                    deferred.complete(block())
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                }
            }) {
            throw IllegalStateException("GL HandlerThread is dead")
        }
        return deferred.await()
    }

    companion object {
        private const val TAG = "HTML2ReelPipeline"
    }
}
