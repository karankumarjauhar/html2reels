package com.html2reel.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.html2reel.model.RecentProject
import com.html2reel.ui.theme.StudioBorder
import com.html2reel.ui.theme.StudioCoral
import com.html2reel.ui.theme.StudioCyan
import com.html2reel.ui.theme.StudioIndigo
import com.html2reel.ui.theme.StudioSurface
import com.html2reel.ui.theme.StudioSurfaceElevated
import com.html2reel.ui.theme.StudioTextMuted

@Composable
fun HomeScreen(
    recentProjects: List<RecentProject>,
    onImportHtmlClick: () -> Unit,
    onPasteHtmlSubmit: (title: String, html: String) -> Unit,
    onOpenSample: (assetFileName: String, title: String) -> Unit,
    onOpenRecent: (RecentProject) -> Unit,
    onDeleteRecent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPasteDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(listOf(StudioIndigo, StudioCoral))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MovieCreation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "HTML2Reel Studio",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "100% Offline Deterministic HTML5 → MP4",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Create New Video",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Import a self-contained HTML5 + CSS/Canvas/SVG file or paste code directly. Zero screen recording, exact frame timestamps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onImportHtmlClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioIndigo)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Import .html")
                        }
                        OutlinedButton(
                            onClick = { showPasteDialog = true },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, StudioBorder)
                        ) {
                            Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Paste HTML")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Offline Starter Reels",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SampleReelCard(
                    title = "Hindi Storytelling",
                    subtitle = "Devanagari + Particles (10s)",
                    onClick = {
                        onOpenSample("hindi_motivation_reel.html", "Hindi Storytelling Reel")
                    },
                    modifier = Modifier.weight(1f)
                )
                SampleReelCard(
                    title = "Orbit Canvas",
                    subtitle = "Explicit seekTo() (8s)",
                    onClick = {
                        onOpenSample("kinetic_code_reel.html", "Deterministic Orbit Reel")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (recentProjects.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Projects",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(recentProjects, key = { it.id }) { project ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, StudioBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecent(project) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(StudioSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = StudioCyan
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${project.resolutionLabel} · ${project.durationSec.toInt()}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioTextMuted
                            )
                        }
                        IconButton(onClick = { onDeleteRecent(project.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove project",
                                tint = StudioTextMuted
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPasteDialog) {
        PasteHtmlDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { title, html ->
                showPasteDialog = false
                onPasteHtmlSubmit(title, html)
            }
        )
    }
}

@Composable
private fun SampleReelCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
        border = BorderStroke(1.dp, StudioBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = StudioCoral,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(text = title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = subtitle, style = MaterialTheme.typography.labelMedium, color = StudioTextMuted)
        }
    }
}

@Composable
private fun PasteHtmlDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, html: String) -> Unit
) {
    var title by remember { mutableStateOf("Pasted Reel") }
    var code by remember {
        mutableStateOf(
            """<!DOCTYPE html>
<html>
<head><meta name="video-duration" content="6"><style>
body { margin:0; width:1080px; height:1920px; display:flex; align-items:center; justify-content:center; background:#0b0f1c; color:#fff; font-size:72px; font-family:sans-serif; }
</style></head>
<body><div id="msg">Hello HTML2Reel</div>
<script>window.seekTo = (t) => { msg.style.transform = 'scale(' + (1 + Math.sin(t * 3) * 0.15) + ')'; };</script>
</body></html>"""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = { Text("Paste HTML5 Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Project Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Self-contained HTML") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title.trim(), code) },
                enabled = code.isNotBlank()
            ) {
                Text("Open Studio")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
