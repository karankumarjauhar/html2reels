package com.html2reel.ui.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.html2reel.model.AspectPreset
import com.html2reel.model.AudioConfig
import com.html2reel.model.RenderConfig
import com.html2reel.model.RenderSpeedMode
import com.html2reel.ui.components.HtmlPreviewPlayer
import com.html2reel.ui.theme.StudioBg
import com.html2reel.ui.theme.StudioBorder
import com.html2reel.ui.theme.StudioCoral
import com.html2reel.ui.theme.StudioCyan
import com.html2reel.ui.theme.StudioIndigo
import com.html2reel.ui.theme.StudioSurface
import com.html2reel.ui.theme.StudioTextMuted
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    config: RenderConfig,
    previewHtml: String,
    hasExplicitSeekHook: Boolean,
    onBack: () -> Unit,
    onConfigChange: ((RenderConfig) -> RenderConfig) -> Unit,
    onAudioChange: ((AudioConfig) -> AudioConfig) -> Unit,
    onPickVoiceover: () -> Unit,
    onPickBgm: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var previewTimeSec by remember(config.projectId) { mutableFloatStateOf(0f) }
    var isPlaying by remember(config.projectId) { mutableStateOf(false) }
    var durationInput by remember(config.projectId, config.durationSec) {
        mutableStateOf(config.durationSec.toInt().toString())
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = StudioBg),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            title = {
                Column {
                    Text(
                        text = config.projectTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (hasExplicitSeekHook) "Hook: window.seekTo()" else "Hook: Virtual Clock + WAAPI",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan
                    )
                }
            },
            actions = {
                Button(
                    onClick = onExportClick,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioCoral),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export MP4")
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, StudioBorder),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HtmlPreviewPlayer(
                            preprocessedHtml = previewHtml,
                            preset = config.preset,
                            currentTimeSec = previewTimeSec,
                            isPlaying = isPlaying,
                            durationSec = config.durationSec,
                            onTimeAdvance = { previewTimeSec = it }
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(onClick = { isPlaying = !isPlaying }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = StudioCyan
                                )
                            }
                            Slider(
                                value = previewTimeSec.coerceIn(0f, config.durationSec.coerceAtLeast(0.1f)),
                                onValueChange = {
                                    isPlaying = false
                                    previewTimeSec = it
                                },
                                valueRange = 0f..config.durationSec.coerceAtLeast(0.1f),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fs / %.0fs", previewTimeSec, config.durationSec),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
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
                        Text("Export Settings", style = MaterialTheme.typography.titleMedium)

                        Text("Resolution & Aspect Ratio", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AspectPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = config.preset == preset,
                                    onClick = { onConfigChange { it.copy(preset = preset) } },
                                    label = { Text(preset.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioIndigo,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                        Text("Export Speed & Quality", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RenderSpeedMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = config.speedMode == mode,
                                    onClick = { onConfigChange { it.copy(speedMode = mode) } },
                                    label = { Text(mode.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioIndigo,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                        val renderW = config.internalRenderWidth()
                        val renderH = config.internalRenderHeight()
                        val speedCaption = if (renderW != config.preset.width || renderH != config.preset.height) {
                            "WebView renders ${renderW}×$renderH → GPU upscales to ${config.preset.width}×${config.preset.height} · ${config.speedMode.summary}"
                        } else {
                            "Native ${config.preset.width}×${config.preset.height} · ${config.speedMode.summary}"
                        }
                        Text(
                            text = "$speedCaption (Use 24 fps for max speed)",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextMuted
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Frame Rate (fps)", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(24, 30, 60).forEach { fps ->
                                        FilterChip(
                                            selected = config.fps == fps,
                                            onClick = { onConfigChange { it.copy(fps = fps) } },
                                            label = { Text("$fps") }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = durationInput,
                                onValueChange = { raw ->
                                    durationInput = raw.filter { it.isDigit() || it == '.' }
                                    durationInput.toFloatOrNull()?.let { sec ->
                                        if (sec in 1f..600f) {
                                            onConfigChange { it.copy(durationSec = sec) }
                                        }
                                    }
                                },
                                label = { Text("Duration (s)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(120.dp)
                            )
                        }

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Video Bitrate (CBR)", style = MaterialTheme.typography.labelMedium)
                                Text("${config.bitrateMbps} Mbps", style = MaterialTheme.typography.labelMedium, color = StudioCyan)
                            }
                            Slider(
                                value = config.bitrateMbps.toFloat(),
                                onValueChange = { v ->
                                    onConfigChange { it.copy(bitrateMbps = v.roundToInt().coerceIn(6, 16)) }
                                },
                                valueRange = 6f..16f,
                                steps = 9
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Hide HUD / Controls", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Hides .hud, #hud, [data-export-hide] and presses 'h'",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StudioTextMuted
                                )
                            }
                            Switch(
                                checked = config.autoHideHud,
                                onCheckedChange = { checked -> onConfigChange { it.copy(autoHideHud = checked) } }
                            )
                        }
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
                        Text("Audio Mixer (48 kHz AAC)", style = MaterialTheme.typography.titleMedium)

                        AudioTrackRow(
                            label = "Voiceover",
                            fileName = config.audio.voiceoverName,
                            volume = config.audio.voiceVolume,
                            maxVolume = 2.0f,
                            icon = Icons.Rounded.AudioFile,
                            onPick = onPickVoiceover,
                            onClear = {
                                onAudioChange { it.copy(voiceoverUri = null, voiceoverName = null) }
                            },
                            onVolumeChange = { vol -> onAudioChange { it.copy(voiceVolume = vol) } }
                        )

                        AudioTrackRow(
                            label = "Background Music (BGM)",
                            fileName = config.audio.bgmName,
                            volume = config.audio.bgmVolume,
                            maxVolume = 1.5f,
                            icon = Icons.Rounded.MusicNote,
                            onPick = onPickBgm,
                            onClear = {
                                onAudioChange { it.copy(bgmUri = null, bgmName = null) }
                            },
                            onVolumeChange = { vol -> onAudioChange { it.copy(bgmVolume = vol) } }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("BGM Auto-Ducking", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Smooth RMS envelope dips BGM under voiceover",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StudioTextMuted
                                )
                            }
                            Switch(
                                checked = config.audio.autoDuckBgm,
                                onCheckedChange = { checked -> onAudioChange { it.copy(autoDuckBgm = checked) } }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    String.format(Locale.US, "Fade In: %.1fs", config.audio.fadeInSec),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(
                                    value = config.audio.fadeInSec,
                                    onValueChange = { v -> onAudioChange { it.copy(fadeInSec = v) } },
                                    valueRange = 0f..5f
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    String.format(Locale.US, "Fade Out: %.1fs", config.audio.fadeOutSec),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(
                                    value = config.audio.fadeOutSec,
                                    onValueChange = { v -> onAudioChange { it.copy(fadeOutSec = v) } },
                                    valueRange = 0f..5f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTrackRow(
    label: String,
    fileName: String?,
    volume: Float,
    maxVolume: Float,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = fileName ?: "None selected (.mp3, .wav, .m4a, .ogg)",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (fileName != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = StudioTextMuted)
                }
            }
            OutlinedButton(onClick = onPick) {
                Text(if (fileName == null) "Select" else "Change")
            }
        }
        if (fileName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(volume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(44.dp)
                )
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..maxVolume,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
