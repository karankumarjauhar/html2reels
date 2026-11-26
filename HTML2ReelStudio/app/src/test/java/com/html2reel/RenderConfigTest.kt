package com.html2reel

import com.html2reel.model.AspectPreset
import com.html2reel.model.AudioConfig
import com.html2reel.model.RenderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderConfigTest {

    @Test
    fun calculates_exact_total_frames_and_clamps_bitrate() {
        val cfg = RenderConfig(
            projectId = "test-1",
            projectTitle = "Sample",
            rawHtml = "<html></html>",
            preset = AspectPreset.REEL_FHD,
            fps = 60,
            durationSec = 12.5f,
            bitrateMbps = 22
        )
        assertEquals(750, cfg.totalFrames)
        assertEquals(16_000_000, cfg.bitrateBps)
        assertEquals(9f / 16f, cfg.preset.aspectRatio, 0.001f)
    }

    @Test
    fun audio_config_detects_tracks_accurately() {
        assertFalse(AudioConfig().hasAnyTrack)
        assertTrue(AudioConfig(voiceoverUri = "content://media/1").hasAnyTrack)
        assertTrue(AudioConfig(bgmUri = "content://media/2").hasAnyTrack)
    }

    @Test
    fun monotonic_pts_nanosecond_math_has_no_backward_steps() {
        val fps = 30L
        var prev = -1L
        for (frame in 0 until 1800) {
            val ptsNs = (frame.toLong() * 1_000_000_000L) / fps
            assertTrue("PTS must strictly increase at frame $frame", ptsNs > prev)
            prev = ptsNs
        }
    }
}
