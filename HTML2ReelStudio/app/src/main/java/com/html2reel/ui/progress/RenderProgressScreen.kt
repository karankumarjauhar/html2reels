package com.html2reel.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.html2reel.model.RenderState
import com.html2reel.ui.theme.StudioBorder
import com.html2reel.ui.theme.StudioCoral
import com.html2reel.ui.theme.StudioCyan
import com.html2reel.ui.theme.StudioIndigo
import com.html2reel.ui.theme.StudioSurface
import com.html2reel.ui.theme.StudioTextMuted
import java.util.Locale

@Composable
fun RenderProgressScreen(
    state: RenderState.Rendering,
    projectTitle: String,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Deterministic Export",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = projectTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextMuted
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val thumb = state.previewThumbnail
                if (thumb != null && !thumb.isRecycled) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = "Frame preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.MovieFilter,
                            contentDescription = null,
                            tint = StudioIndigo,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.phaseLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextMuted
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = state.phaseLabel,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    color = StudioCyan
                )
            }

            LinearProgressIndicator(
                progress = { state.progress },
                color = StudioIndigo,
                trackColor = StudioSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricChip("Frames", "${state.currentFrame} / ${state.totalFrames}")
                MetricChip("Elapsed", formatMs(state.elapsedMs))
                MetricChip("ETA", if (state.etaMs > 0) formatMs(state.etaMs) else "—")
            }

            OutlinedButton(
                onClick = onCancelClick,
                border = BorderStroke(1.dp, StudioCoral),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Cancel Export", color = StudioCoral, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = StudioTextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

private fun formatMs(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    val m = sec / 60L
    val s = sec % 60L
    return String.format(Locale.US, "%02d:%02d", m, s)
}
