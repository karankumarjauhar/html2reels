package com.html2reel.ui.export

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.html2reel.model.RenderState
import com.html2reel.ui.theme.StudioAmber
import com.html2reel.ui.theme.StudioBorder
import com.html2reel.ui.theme.StudioCyan
import com.html2reel.ui.theme.StudioIndigo
import com.html2reel.ui.theme.StudioSurface
import com.html2reel.ui.theme.StudioTextMuted
import java.io.File
import java.util.Locale

@Composable
fun ExportCompleteScreen(
    state: RenderState.Completed,
    isSavingToGallery: Boolean,
    gallerySavedUri: Uri?,
    onSaveToGallery: () -> Unit,
    onShareClick: () -> Unit,
    onBackToStudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(state.outputPath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(state.outputPath))))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (state.isPartial) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (state.isPartial) StudioAmber else StudioCyan,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = if (state.isPartial) "Partial Export Saved" else "Export Complete",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1fs · %d frames · %.2f MB",
                        state.durationMs / 1000.0,
                        state.videoFrames,
                        state.fileSizeBytes / (1024.0 * 1024.0)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextMuted
                )
            }
        }

        state.warningMessage?.let { warn ->
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = warn,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            useController = true
                            this.player = player
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onSaveToGallery,
                enabled = !isSavingToGallery && gallerySavedUri == null,
                colors = ButtonDefaults.buttonColors(containerColor = StudioIndigo),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                if (isSavingToGallery) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (gallerySavedUri != null) Icons.Rounded.DownloadDone else Icons.Rounded.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (gallerySavedUri != null) "Saved" else "Save to Gallery")
                }
            }

            OutlinedButton(
                onClick = onShareClick,
                border = BorderStroke(1.dp, StudioBorder),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
        }

        TextButton(
            onClick = onBackToStudio,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Back to Studio")
        }
    }
}
