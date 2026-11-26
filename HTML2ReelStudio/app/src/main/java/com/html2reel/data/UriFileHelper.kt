package com.html2reel.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object UriFileHelper {

    fun readTextFromUri(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            return stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("Unable to open HTML URI: $uri")
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported_file"
    }

    /**
     * Stages a SAF content:// audio document into app cache while preserving its extension
     * so Symphonia's format probe receives an accurate hint.
     */
    fun stageUriToCache(context: Context, uriString: String, prefix: String): File {
        val uri = Uri.parse(uriString)
        val displayName = resolveDisplayName(context, uri)
        val ext = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("mp3", "wav", "m4a", "aac", "ogg", "flac") }
            ?: inferAudioExtFromMime(context.contentResolver.getType(uri))
            ?: "bin"

        val cacheDir = File(context.cacheDir, "audio_inputs").apply { mkdirs() }
        val outFile = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: throw IllegalStateException("Unable to read audio URI: $uriString")
        return outFile
    }

    private fun inferAudioExtFromMime(mime: String?): String? = when (mime?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/mp4", "audio/aac", "audio/x-m4a", "audio/mp4a-latm" -> "m4a"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/flac" -> "flac"
        else -> null
    }
}
