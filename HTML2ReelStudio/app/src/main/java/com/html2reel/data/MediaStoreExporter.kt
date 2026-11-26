package com.html2reel.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStoreExporter {

    suspend fun saveToGallery(context: Context, mp4File: File, displayTitle: String): Uri =
        withContext(Dispatchers.IO) {
            val cleanName = displayTitle
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "HTML2Reel_${System.currentTimeMillis()}" }
            val fileName = if (cleanName.endsWith(".mp4", ignoreCase = true)) cleanName else "$cleanName.mp4"

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/HTML2Reel"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val moviesDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "HTML2Reel"
                    ).apply { mkdirs() }
                    val legacyOut = File(moviesDir, fileName)
                    put(MediaStore.Video.Media.DATA, legacyOut.absolutePath)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore failed to allocate video entry")

            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(mp4File).use { input ->
                        input.copyTo(out, bufferSize = 256 * 1024)
                    }
                } ?: throw IllegalStateException("Unable to open MediaStore output stream")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publish = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, publish, null, null)
                }
                itemUri
            } catch (t: Throwable) {
                resolver.delete(itemUri, null, null)
                throw t
            }
        }

    fun buildShareIntent(context: Context, mp4File: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            mp4File
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun copyForExport(src: File, dstDir: File): File {
        dstDir.mkdirs()
        val dst = File(dstDir, src.name)
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, bufferSize = 256 * 1024)
            }
        }
        return dst
    }
}
