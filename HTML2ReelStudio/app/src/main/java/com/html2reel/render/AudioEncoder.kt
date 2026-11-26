package com.html2reel.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import uniffi.html2reel_core.AudioMixSession
import uniffi.html2reel_core.Mp4MuxerSession
import java.nio.ByteBuffer

/**
 * Streams 16-bit LE stereo PCM chunks from Rust's AudioMixSession through
 * Android hardware AAC-LC MediaCodec and emits raw AAC packets to Mp4MuxerSession.
 */
class AudioEncoder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    bitrate: Int = 192_000
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var configSent = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encodeSession(
        audioSession: AudioMixSession,
        muxer: Mp4MuxerSession,
        maxFramesToEncode: Long? = null,
        isCancelled: () -> Boolean
    ) {
        val bytesPerFrame = channels * 2
        var framesSubmitted = 0L
        var inputDone = false
        var outputDone = false
        var pendingChunk = ByteArray(0)
        var pendingOffset = 0

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        if (isCancelled() || (maxFramesToEncode != null && framesSubmitted >= maxFramesToEncode)) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0,
                                (framesSubmitted * 1_000_000L) / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            if (pendingOffset >= pendingChunk.size) {
                                val remainingCap = maxFramesToEncode?.let { (it - framesSubmitted).coerceAtMost(4096L).toInt() } ?: 4096
                                pendingChunk = if (remainingCap > 0) {
                                    audioSession.readPcmChunk(remainingCap.toUInt())
                                } else {
                                    ByteArray(0)
                                }
                                pendingOffset = 0
                            }

                            if (pendingChunk.isEmpty()) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0,
                                    (framesSubmitted * 1_000_000L) / sampleRate,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val toCopy = minOf(inBuf.remaining(), pendingChunk.size - pendingOffset)
                                val alignedCopy = (toCopy / bytesPerFrame) * bytesPerFrame
                                if (alignedCopy > 0) {
                                    val ptsUs = (framesSubmitted * 1_000_000L) / sampleRate
                                    inBuf.put(pendingChunk, pendingOffset, alignedCopy)
                                    pendingOffset += alignedCopy
                                    framesSubmitted += alignedCopy / bytesPerFrame
                                    codec.queueInputBuffer(inIdx, 0, alignedCopy, ptsUs, 0)
                                } else {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0,
                                        (framesSubmitted * 1_000_000L) / sampleRate,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                }
                            }
                        }
                    }
                }
            }

            while (true) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val csd0 = codec.outputFormat.getByteBuffer("csd-0")?.toByteArray()
                        if (csd0 != null) {
                            muxer.setAudioConfig(csd0)
                            configSent = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outBuf.get(bytes)

                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (isConfig) {
                                if (!configSent && bytes.size >= 2) {
                                    muxer.setAudioConfig(bytes)
                                    configSent = true
                                }
                            } else {
                                muxer.writeAudioSample(
                                    data = bytes,
                                    ptsUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L).toULong()
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    }
                }
            }
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val dup = duplicate()
        dup.rewind()
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }
}
