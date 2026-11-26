package com.html2reel.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import uniffi.html2reel_core.Mp4MuxerSession
import java.nio.ByteBuffer

/**
 * Hardware H.264/AVC encoder using a Surface input and CBR rate control for deterministic output.
 */
class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrateBps: Int
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface

    private var configSent = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var eosSignaled = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encCaps = runCatching {
                codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
            }.getOrNull()
            if (encCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun drain(endOfStream: Boolean, muxer: Mp4MuxerSession) {
        if (endOfStream && !eosSignaled) {
            eosSignaled = true
            codec.signalEndOfInputStream()
        }

        val timeoutUs = if (endOfStream) 10_000L else 0L
        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    sendConfigFromFormat(codec.outputFormat, muxer)
                }
                status >= 0 -> {
                    val outBuf = codec.getOutputBuffer(status)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        outBuf.get(bytes)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (isConfig) {
                            if (!configSent) {
                                muxer.setVideoConfig(bytes, null)
                                configSent = true
                            }
                        } else {
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            muxer.writeVideoSample(
                                data = bytes,
                                ptsUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L).toULong(),
                                isKeyframe = isKeyFrame
                            )
                        }
                    }
                    codec.releaseOutputBuffer(status, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun sendConfigFromFormat(format: MediaFormat, muxer: Mp4MuxerSession) {
        val csd0 = format.getByteBuffer("csd-0")?.toByteArray() ?: return
        val csd1 = format.getByteBuffer("csd-1")?.toByteArray()
        muxer.setVideoConfig(csd0, csd1)
        configSent = true
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val dup = duplicate()
        dup.rewind()
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }
}
