//! Offline audio decoder (Symphonia), resampler (Rubato), and RMS auto-ducking mixer.

use std::fs::File;
use std::path::Path;
use std::sync::Mutex;

use audioadapter_buffers::direct::InterleavedSlice;
use audioadapter_buffers::owned::InterleavedOwned;
use rubato::{Fft, FixedSync, Resampler};
use symphonia::core::codecs::audio::AudioDecoderOptions;
use symphonia::core::codecs::CodecParameters;
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, TrackType};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;

use crate::StudioError;

pub const TARGET_SAMPLE_RATE: usize = 48_000;
pub const TARGET_CHANNELS: usize = 2;

/// User-configurable audio mixing options passed from Android Audio Mixer UI.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AudioMixConfig {
    pub voiceover_path: Option<String>,
    pub bgm_path: Option<String>,
    pub duration_sec: f64,
    pub voice_volume: f32,
    pub bgm_volume: f32,
    pub auto_duck_bgm: bool,
    pub fade_in_sec: f64,
    pub fade_out_sec: f64,
}

struct MixedAudioState {
    /// Interleaved stereo 16-bit PCM bytes (little-endian) ready for AAC MediaCodec input.
    pcm_i16_le: Vec<u8>,
    cursor_bytes: usize,
    total_frames: u64,
}

/// UniFFI-exported audio mixer session that decodes, resamples, mixes, ducks,
/// and streams bounded PCM chunks without large cross-FFI buffer allocations.
#[derive(uniffi::Object)]
pub struct AudioMixSession {
    state: Mutex<MixedAudioState>,
}

#[uniffi::export]
impl AudioMixSession {
    #[uniffi::constructor]
    pub fn new(config: AudioMixConfig) -> Result<std::sync::Arc<Self>, StudioError> {
        let mixed_f32 = mix_audio_tracks(&config)?;
        let total_frames = (mixed_f32.len() / TARGET_CHANNELS) as u64;
        let mut pcm_i16_le = Vec::with_capacity(mixed_f32.len() * 2);
        for s in mixed_f32 {
            let clamped = soft_limit(s);
            let sample_i16 = (clamped * i16::MAX as f32).round() as i16;
            pcm_i16_le.extend_from_slice(&sample_i16.to_le_bytes());
        }
        Ok(std::sync::Arc::new(Self {
            state: Mutex::new(MixedAudioState {
                pcm_i16_le,
                cursor_bytes: 0,
                total_frames,
            }),
        }))
    }

    pub fn total_frames(&self) -> u64 {
        self.state.lock().map(|s| s.total_frames).unwrap_or(0)
    }

    pub fn sample_rate(&self) -> u32 {
        TARGET_SAMPLE_RATE as u32
    }

    pub fn channels(&self) -> u16 {
        TARGET_CHANNELS as u16
    }

    /// Reads up to `max_frames` of interleaved 16-bit LE stereo PCM; returns empty at EOF.
    pub fn read_pcm_chunk(&self, max_frames: u32) -> Vec<u8> {
        let Ok(mut state) = self.state.lock() else {
            return Vec::new();
        };
        if state.cursor_bytes >= state.pcm_i16_le.len() || max_frames == 0 {
            return Vec::new();
        }
        let max_bytes = (max_frames as usize) * TARGET_CHANNELS * 2;
        let end = (state.cursor_bytes + max_bytes).min(state.pcm_i16_le.len());
        let chunk = state.pcm_i16_le[state.cursor_bytes..end].to_vec();
        state.cursor_bytes = end;
        chunk
    }
}

/// Decodes, resamples to 48 kHz stereo, loops/trims, ducks BGM under Voiceover, and applies fades.
pub fn mix_audio_tracks(config: &AudioMixConfig) -> Result<Vec<f32>, StudioError> {
    if !config.duration_sec.is_finite() || config.duration_sec <= 0.0 {
        return Err(StudioError::AudioError {
            detail: "Duration must be positive".into(),
        });
    }

    let total_frames = ((config.duration_sec * TARGET_SAMPLE_RATE as f64).round() as usize).max(1);
    let total_samples = total_frames * TARGET_CHANNELS;

    let voice_stereo = match config.voiceover_path.as_deref() {
        Some(path) if !path.trim().is_empty() => {
            Some(decode_and_resample_to_48k_stereo(Path::new(path))?)
        }
        _ => None,
    };

    let bgm_stereo = match config.bgm_path.as_deref() {
        Some(path) if !path.trim().is_empty() => {
            Some(decode_and_resample_to_48k_stereo(Path::new(path))?)
        }
        _ => None,
    };

    let mut out = vec![0.0f32; total_samples];

    // Pre-compute smooth RMS ducking curve from Voiceover when both tracks exist.
    let duck_envelope: Option<Vec<f32>> = match (&voice_stereo, &bgm_stereo) {
        (Some(v), Some(_)) if config.auto_duck_bgm && !v.is_empty() => {
            Some(compute_duck_envelope(v, total_frames))
        }
        _ => None,
    };

    let voice_gain = config.voice_volume.clamp(0.0, 2.0);
    let bgm_gain = config.bgm_volume.clamp(0.0, 1.5);

    if let Some(bgm) = &bgm_stereo {
        let bgm_frames = bgm.len() / TARGET_CHANNELS;
        if bgm_frames > 0 {
            let crossfade_frames = (TARGET_SAMPLE_RATE / 50).min(bgm_frames / 4); // 20ms loop blend
            for f in 0..total_frames {
                let src_f = f % bgm_frames;
                let duck = duck_envelope.as_ref().map(|env| env[f]).unwrap_or(1.0);
                let mut l = bgm[src_f * 2];
                let mut r = bgm[src_f * 2 + 1];

                // Apply tiny loop boundary crossfade after the first repetition.
                if f >= bgm_frames && crossfade_frames > 0 && src_f < crossfade_frames {
                    let tail_f = bgm_frames - crossfade_frames + src_f;
                    let alpha = src_f as f32 / crossfade_frames as f32;
                    l = l * alpha + bgm[tail_f * 2] * (1.0 - alpha);
                    r = r * alpha + bgm[tail_f * 2 + 1] * (1.0 - alpha);
                }

                out[f * 2] += l * bgm_gain * duck;
                out[f * 2 + 1] += r * bgm_gain * duck;
            }
        }
    }

    if let Some(voice) = &voice_stereo {
        let copy_frames = (voice.len() / TARGET_CHANNELS).min(total_frames);
        for f in 0..copy_frames {
            out[f * 2] += voice[f * 2] * voice_gain;
            out[f * 2 + 1] += voice[f * 2 + 1] * voice_gain;
        }
    }

    apply_fades(&mut out, total_frames, config.fade_in_sec, config.fade_out_sec);
    Ok(out)
}

/// Decodes any Symphonia-supported audio file and resamples to 48,000 Hz stereo `f32`.
pub fn decode_and_resample_to_48k_stereo(path: &Path) -> Result<Vec<f32>, StudioError> {
    let file = File::open(path).map_err(|e| StudioError::IoError {
        detail: format!("Cannot open audio file '{}': {e}", path.display()),
    })?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
        hint.with_extension(ext);
    }

    let mut format = symphonia::default::get_probe()
        .probe(&hint, mss, FormatOptions::default(), MetadataOptions::default())
        .map_err(|e| StudioError::AudioError {
            detail: format!("Failed probing audio '{}': {e}", path.display()),
        })?;

    let (track_id, audio_params) = {
        let track = format
            .default_track(TrackType::Audio)
            .or_else(|| {
                format.tracks().iter().find(|t| {
                    matches!(t.codec_params, Some(CodecParameters::Audio(_)))
                })
            })
            .ok_or_else(|| StudioError::AudioError {
                detail: format!("No audio track found in '{}'", path.display()),
            })?;

        let Some(CodecParameters::Audio(params)) = &track.codec_params else {
            return Err(StudioError::AudioError {
                detail: format!("Track in '{}' has no audio codec params", path.display()),
            });
        };
        (track.id, params.clone())
    };

    let track_sample_rate = audio_params.sample_rate.unwrap_or(48_000);
    let mut decoder = symphonia::default::get_codecs()
        .make_audio_decoder(&audio_params, &AudioDecoderOptions::default())
        .map_err(|e| StudioError::AudioError {
            detail: format!("Unsupported audio codec in '{}': {e}", path.display()),
        })?;

    let mut packet_interleaved: Vec<f32> = Vec::new();
    let mut stereo_interleaved: Vec<f32> = Vec::new();
    let mut detected_sample_rate: usize = track_sample_rate as usize;

    loop {
        let packet = match format.next_packet() {
            Ok(Some(pkt)) => pkt,
            Ok(None) => break,
            Err(SymphoniaError::IoError(err))
                if err.kind() == std::io::ErrorKind::UnexpectedEof =>
            {
                break;
            }
            Err(SymphoniaError::ResetRequired) => {
                decoder.reset();
                continue;
            }
            Err(e) => {
                return Err(StudioError::AudioError {
                    detail: format!("Error reading audio packet: {e}"),
                });
            }
        };

        if packet.track_id != track_id {
            continue;
        }

        match decoder.decode(&packet) {
            Ok(decoded) => {
                let spec = decoded.spec().clone();
                detected_sample_rate = spec.rate() as usize;
                let src_channels = spec.channels().count().max(1);

                decoded.copy_to_vec_interleaved::<f32>(&mut packet_interleaved);

                for frame in packet_interleaved.chunks_exact(src_channels) {
                    match src_channels {
                        1 => {
                            stereo_interleaved.push(frame[0]);
                            stereo_interleaved.push(frame[0]);
                        }
                        2 => {
                            stereo_interleaved.push(frame[0]);
                            stereo_interleaved.push(frame[1]);
                        }
                        n => {
                            // Downmix multi-channel audio cleanly to stereo.
                            let l = frame[0];
                            let r = frame[1];
                            let center = if n >= 3 { frame[2] * 0.707 } else { 0.0 };
                            stereo_interleaved.push((l + center) * 0.75);
                            stereo_interleaved.push((r + center) * 0.75);
                        }
                    }
                }
            }
            Err(SymphoniaError::DecodeError(_)) => continue,
            Err(SymphoniaError::IoError(err))
                if err.kind() == std::io::ErrorKind::UnexpectedEof =>
            {
                break;
            }
            Err(e) => {
                return Err(StudioError::AudioError {
                    detail: format!("Audio decode error: {e}"),
                });
            }
        }
    }

    if stereo_interleaved.is_empty() || detected_sample_rate == TARGET_SAMPLE_RATE {
        return Ok(stereo_interleaved);
    }

    resample_stereo_f32(&stereo_interleaved, detected_sample_rate, TARGET_SAMPLE_RATE)
}

fn resample_stereo_f32(
    input_stereo: &[f32],
    rate_in: usize,
    rate_out: usize,
) -> Result<Vec<f32>, StudioError> {
    let input_frames = input_stereo.len() / TARGET_CHANNELS;
    if input_frames == 0 {
        return Ok(Vec::new());
    }

    let chunk_size = 1024.min(input_frames.max(64));
    let mut resampler = Fft::<f32>::new(
        rate_in,
        rate_out,
        chunk_size,
        TARGET_CHANNELS,
        FixedSync::Both,
    )
    .map_err(|e| StudioError::AudioError {
        detail: format!("Failed to initialize Rubato resampler ({rate_in}->{rate_out}): {e}"),
    })?;

    let input_adapter = InterleavedSlice::new(input_stereo, TARGET_CHANNELS, input_frames)
        .map_err(|e| StudioError::AudioError {
            detail: format!("Invalid input adapter layout: {e}"),
        })?;
    let needed_out = resampler.process_all_needed_output_len(input_frames);
    let mut output_adapter = InterleavedOwned::<f32>::new(0.0, TARGET_CHANNELS, needed_out);

    let (_consumed, written_frames) = resampler
        .process_all_into_buffer(&input_adapter, &mut output_adapter, input_frames, None)
        .map_err(|e| StudioError::AudioError {
            detail: format!("Rubato resampling failed: {e}"),
        })?;

    let mut out = output_adapter.take_data();
    out.truncate(written_frames * TARGET_CHANNELS);
    Ok(out)
}

/// Computes an asymmetric RMS envelope follower (fast 35ms attack, smooth 300ms release)
/// so background music dips naturally under speech without pumping.
fn compute_duck_envelope(voice_stereo: &[f32], total_frames: usize) -> Vec<f32> {
    let window_frames = (TARGET_SAMPLE_RATE * 30) / 1000; // 30ms sliding RMS window
    let voice_frames = voice_stereo.len() / TARGET_CHANNELS;
    let mut window_buf = vec![0.0f32; window_frames];
    let mut sum_sq: f64 = 0.0;

    let alpha_attack = 1.0f32 - (-1.0f32 / (0.035 * TARGET_SAMPLE_RATE as f32)).exp();
    let alpha_release = 1.0f32 - (-1.0f32 / (0.300 * TARGET_SAMPLE_RATE as f32)).exp();

    let threshold = 0.018f32; // ~ -35 dBFS speech activity threshold
    let duck_floor = 0.22f32; // -13 dB attenuation when speaking
    let mut env_gain = 1.0f32;
    let mut out = vec![1.0f32; total_frames];

    for f in 0..total_frames {
        let energy = if f < voice_frames {
            let l = voice_stereo[f * 2];
            let r = voice_stereo[f * 2 + 1];
            0.5 * (l * l + r * r)
        } else {
            0.0
        };

        let slot = f % window_frames;
        sum_sq = (sum_sq - window_buf[slot] as f64 + energy as f64).max(0.0);
        window_buf[slot] = energy;
        let rms = ((sum_sq / window_frames as f64) as f32).sqrt();

        let target = if rms > threshold {
            let ratio = ((rms - threshold) / (threshold * 3.0)).clamp(0.0, 1.0);
            1.0 - ratio * (1.0 - duck_floor)
        } else {
            1.0
        };

        let alpha = if target < env_gain {
            alpha_attack
        } else {
            alpha_release
        };
        env_gain += alpha * (target - env_gain);
        out[f] = env_gain;
    }

    out
}

fn apply_fades(samples: &mut [f32], total_frames: usize, fade_in_sec: f64, fade_out_sec: f64) {
    let fade_in_frames = ((fade_in_sec.max(0.0) * TARGET_SAMPLE_RATE as f64).round() as usize)
        .min(total_frames);
    let fade_out_frames = ((fade_out_sec.max(0.0) * TARGET_SAMPLE_RATE as f64).round() as usize)
        .min(total_frames);

    for f in 0..fade_in_frames {
        let g = if fade_in_frames > 1 {
            f as f32 / (fade_in_frames - 1) as f32
        } else {
            1.0
        };
        samples[f * 2] *= g;
        samples[f * 2 + 1] *= g;
    }

    for i in 0..fade_out_frames {
        let f = total_frames - 1 - i;
        let g = if fade_out_frames > 1 {
            i as f32 / (fade_out_frames - 1) as f32
        } else {
            1.0
        };
        samples[f * 2] *= g;
        samples[f * 2 + 1] *= g;
    }
}

#[inline]
fn soft_limit(x: f32) -> f32 {
    let ax = x.abs();
    if ax <= 0.95 {
        x
    } else {
        x.signum() * (0.95 + 0.05 * ((ax - 0.95) * 20.0).tanh())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::PI;
    use std::io::Write;
    use tempfile::tempdir;

    fn write_pcm16_wav(path: &Path, sample_rate: u32, channels: u16, samples: &[i16]) {
        let data_bytes = (samples.len() * 2) as u32;
        let riff_size = 36 + data_bytes;
        let byte_rate = sample_rate * channels as u32 * 2;
        let block_align = channels * 2;

        let mut f = File::create(path).unwrap();
        f.write_all(b"RIFF").unwrap();
        f.write_all(&riff_size.to_le_bytes()).unwrap();
        f.write_all(b"WAVE").unwrap();
        f.write_all(b"fmt ").unwrap();
        f.write_all(&16u32.to_le_bytes()).unwrap(); // PCM
        f.write_all(&1u16.to_le_bytes()).unwrap(); // Linear PCM
        f.write_all(&channels.to_le_bytes()).unwrap();
        f.write_all(&sample_rate.to_le_bytes()).unwrap();
        f.write_all(&byte_rate.to_le_bytes()).unwrap();
        f.write_all(&block_align.to_le_bytes()).unwrap();
        f.write_all(&16u16.to_le_bytes()).unwrap();
        f.write_all(b"data").unwrap();
        f.write_all(&data_bytes.to_le_bytes()).unwrap();
        for s in samples {
            f.write_all(&s.to_le_bytes()).unwrap();
        }
    }

    #[test]
    fn resamples_44k1_mono_and_ducks_bgm_during_voiceover() {
        let dir = tempdir().unwrap();
        let bgm_wav = dir.path().join("bgm.wav");
        let voice_wav = dir.path().join("voice.wav");

        // 1.5s constant 220Hz BGM at 44,100 Hz mono
        let bgm_len = (44_100.0 * 1.5) as usize;
        let bgm_samples: Vec<i16> = (0..bgm_len)
            .map(|i| ((2.0 * PI * 220.0 * i as f32 / 44_100.0).sin() * 14_000.0) as i16)
            .collect();
        write_pcm16_wav(&bgm_wav, 44_100, 1, &bgm_samples);

        // 2.0s voiceover: silent first 0.5s, loud 1kHz tone from 0.5s..1.2s, silent after
        let voice_len = 48_000 * 2;
        let voice_samples: Vec<i16> = (0..voice_len)
            .map(|i| {
                let t = i as f32 / 48_000.0;
                if (0.5..1.2).contains(&t) {
                    ((2.0 * PI * 1000.0 * t).sin() * 18_000.0) as i16
                } else {
                    0
                }
            })
            .collect();
        write_pcm16_wav(&voice_wav, 48_000, 1, &voice_samples);

        // Verify ducking lowers BGM gain while voiceover is active
        let session = AudioMixSession::new(AudioMixConfig {
            voiceover_path: Some(voice_wav.to_string_lossy().into_owned()),
            bgm_path: Some(bgm_wav.to_string_lossy().into_owned()),
            duration_sec: 2.0,
            voice_volume: 0.0, // isolate BGM gain envelope during voice activity!
            bgm_volume: 1.0,
            auto_duck_bgm: true,
            fade_in_sec: 0.0,
            fade_out_sec: 0.0,
        })
        .unwrap();

        assert_eq!(session.total_frames(), 96_000);
        let mut all_bytes = Vec::new();
        loop {
            let chunk = session.read_pcm_chunk(4096);
            if chunk.is_empty() {
                break;
            }
            all_bytes.extend_from_slice(&chunk);
        }
        assert_eq!(all_bytes.len(), 96_000 * 2 * 2);

        let samples_i16: Vec<i16> = all_bytes
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]))
            .collect();

        // Measure RMS before duck (0.15s..0.35s) vs during duck (0.75s..0.95s)
        let rms_window = |start_s: f32, end_s: f32| -> f32 {
            let a = (start_s * 48_000.0) as usize * 2;
            let b = (end_s * 48_000.0) as usize * 2;
            let sum: f64 = samples_i16[a..b]
                .iter()
                .map(|&s| (s as f64) * (s as f64))
                .sum();
            (sum / (b - a) as f64).sqrt() as f32
        };

        let rms_unducked = rms_window(0.15, 0.35);
        let rms_ducked = rms_window(0.75, 0.95);
        assert!(
            rms_ducked < rms_unducked * 0.45,
            "Expected ducked RMS ({rms_ducked}) < 45% of unducked ({rms_unducked})"
        );
    }
}
