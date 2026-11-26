//! Pure-Rust Fast-Start ISO-BMFF MP4 muxer (`moov` atom placed before `mdat`).
//! Supports H.264 (Annex-B -> AVCC conversion) + AAC-LC audio with monotonic A/V sync validation.

use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use crate::StudioError;

/// Summary returned after finalizing an MP4 file.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MuxSummary {
    pub output_path: String,
    pub video_frames: u64,
    pub audio_frames: u64,
    pub duration_ms: u64,
    pub file_size_bytes: u64,
    pub av_drift_ms: i64,
}

#[derive(Debug, Clone)]
struct SampleRecord {
    size: u32,
    duration_ticks: u32,
    mdat_rel_offset: u64,
    is_keyframe: bool,
}

/// Stateful streaming MP4 muxer. Writes encoded payloads to a temporary `mdat`
/// file and emits a fast-start (`ftyp` -> `moov` -> `mdat`) `.mp4` on `finish()`.
pub struct Mp4Muxer {
    output_path: PathBuf,
    temp_mdat_path: PathBuf,
    temp_writer: Option<BufWriter<File>>,
    mdat_bytes_written: u64,

    width: u32,
    height: u32,
    fps: u32,
    video_timescale: u32,
    sps_list: Vec<Vec<u8>>,
    pps_list: Vec<Vec<u8>>,
    video_samples: Vec<SampleRecord>,
    last_video_pts_us: Option<u64>,

    has_audio: bool,
    audio_sample_rate: u32,
    audio_channels: u16,
    audio_specific_config: Vec<u8>,
    audio_samples: Vec<SampleRecord>,
    last_audio_pts_us: Option<u64>,

    finished: bool,
}

impl Mp4Muxer {
    /// Creates a new fast-start MP4 muxer targeting `output_path`.
    pub fn new(
        output_path: impl AsRef<Path>,
        width: u32,
        height: u32,
        fps: u32,
        has_audio: bool,
        audio_sample_rate: u32,
        audio_channels: u16,
    ) -> Result<Self, StudioError> {
        if width == 0 || height == 0 || fps == 0 {
            return Err(StudioError::MuxError {
                detail: "Video dimensions and fps must be greater than zero".into(),
            });
        }
        let output_path = output_path.as_ref().to_path_buf();
        if let Some(parent) = output_path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).map_err(|e| StudioError::IoError {
                    detail: format!("Failed to create output directory: {e}"),
                })?;
            }
        }

        let temp_mdat_path = PathBuf::from(format!("{}.mdat.tmp", output_path.display()));
        let temp_file = File::create(&temp_mdat_path).map_err(|e| StudioError::IoError {
            detail: format!("Failed to create temp mdat file: {e}"),
        })?;

        Ok(Self {
            output_path,
            temp_mdat_path,
            temp_writer: Some(BufWriter::with_capacity(256 * 1024, temp_file)),
            mdat_bytes_written: 0,
            width,
            height,
            fps,
            video_timescale: fps * 1000,
            sps_list: Vec::new(),
            pps_list: Vec::new(),
            video_samples: Vec::new(),
            last_video_pts_us: None,
            has_audio,
            audio_sample_rate: if audio_sample_rate == 0 { 48_000 } else { audio_sample_rate },
            audio_channels: if audio_channels == 0 { 2 } else { audio_channels },
            audio_specific_config: Vec::new(),
            audio_samples: Vec::new(),
            last_audio_pts_us: None,
            finished: false,
        })
    }

    /// Sets H.264 SPS/PPS from `MediaCodec` `csd-0` and optional `csd-1`.
    /// Handles both concatenated Annex-B start-code buffers and raw stripped NALs.
    pub fn set_video_config(&mut self, csd0: &[u8], csd1: Option<&[u8]>) -> Result<(), StudioError> {
        self.ingest_config_nals(csd0);
        if let Some(extra) = csd1 {
            self.ingest_config_nals(extra);
        }
        if self.sps_list.is_empty() || self.pps_list.is_empty() {
            return Err(StudioError::MuxError {
                detail: "Missing H.264 SPS or PPS in codec config".into(),
            });
        }
        Ok(())
    }

    /// Sets AAC `AudioSpecificConfig` from `MediaCodec` `csd-0`.
    pub fn set_audio_config(&mut self, csd0: &[u8]) -> Result<(), StudioError> {
        if csd0.len() < 2 {
            return Err(StudioError::MuxError {
                detail: "AAC AudioSpecificConfig must be at least 2 bytes".into(),
            });
        }
        self.audio_specific_config = csd0.to_vec();
        Ok(())
    }

    /// Writes one H.264 access unit (Annex-B or raw NALs), converting it to 4-byte length-prefixed AVCC.
    pub fn write_video_sample(
        &mut self,
        data: &[u8],
        pts_us: u64,
        is_keyframe: bool,
    ) -> Result<(), StudioError> {
        if self.finished {
            return Err(StudioError::MuxError {
                detail: "Cannot write after muxer finished".into(),
            });
        }
        if let Some(prev) = self.last_video_pts_us {
            if pts_us <= prev {
                return Err(StudioError::MuxError {
                    detail: format!("Non-monotonic video PTS: {pts_us} <= {prev}"),
                });
            }
        }

        let nals = split_nals_auto(data);
        let mut avcc = Vec::with_capacity(data.len() + 16);
        for nal in nals {
            if nal.is_empty() {
                continue;
            }
            let nal_type = nal[0] & 0x1F;
            if nal_type == 7 {
                push_unique(&mut self.sps_list, nal);
                continue;
            }
            if nal_type == 8 {
                push_unique(&mut self.pps_list, nal);
                continue;
            }
            let len_u32 = u32::try_from(nal.len()).map_err(|_| StudioError::MuxError {
                detail: "NAL unit exceeds u32 length".into(),
            })?;
            avcc.extend_from_slice(&len_u32.to_be_bytes());
            avcc.extend_from_slice(nal);
        }

        // Ignore pure codec-config packets after extracting SPS/PPS.
        if avcc.is_empty() {
            return Ok(());
        }

        let rel_offset = self.mdat_bytes_written;
        self.ensure_stco_fits(rel_offset + avcc.len() as u64)?;

        let writer = self.temp_writer.as_mut().ok_or_else(|| StudioError::MuxError {
            detail: "Muxer writer closed".into(),
        })?;
        writer.write_all(&avcc).map_err(|e| StudioError::IoError {
            detail: format!("Failed writing video sample to temp mdat: {e}"),
        })?;
        self.mdat_bytes_written += avcc.len() as u64;

        let default_ticks = 1000u32;
        if let Some(prev_pts) = self.last_video_pts_us {
            let delta_us = pts_us - prev_pts;
            let ticks = ((delta_us as u128 * self.video_timescale as u128 + 500_000) / 1_000_000)
                .max(1) as u32;
            if let Some(last) = self.video_samples.last_mut() {
                last.duration_ticks = ticks;
            }
        }
        self.last_video_pts_us = Some(pts_us);

        self.video_samples.push(SampleRecord {
            size: avcc.len() as u32,
            duration_ticks: default_ticks,
            mdat_rel_offset: rel_offset,
            is_keyframe,
        });
        Ok(())
    }

    /// Writes one raw AAC access unit.
    pub fn write_audio_sample(&mut self, data: &[u8], pts_us: u64) -> Result<(), StudioError> {
        if self.finished {
            return Err(StudioError::MuxError {
                detail: "Cannot write after muxer finished".into(),
            });
        }
        if !self.has_audio || data.is_empty() {
            return Ok(());
        }
        if let Some(prev) = self.last_audio_pts_us {
            if pts_us < prev {
                return Err(StudioError::MuxError {
                    detail: format!("Non-monotonic audio PTS: {pts_us} < {prev}"),
                });
            }
        }

        let rel_offset = self.mdat_bytes_written;
        self.ensure_stco_fits(rel_offset + data.len() as u64)?;

        let writer = self.temp_writer.as_mut().ok_or_else(|| StudioError::MuxError {
            detail: "Muxer writer closed".into(),
        })?;
        writer.write_all(data).map_err(|e| StudioError::IoError {
            detail: format!("Failed writing audio sample to temp mdat: {e}"),
        })?;
        self.mdat_bytes_written += data.len() as u64;

        let ticks_per_aac_frame = 1024u32;
        self.last_audio_pts_us = Some(pts_us);
        self.audio_samples.push(SampleRecord {
            size: data.len() as u32,
            duration_ticks: ticks_per_aac_frame,
            mdat_rel_offset: rel_offset,
            is_keyframe: true,
        });
        Ok(())
    }

    /// Finalizes `moov` atom before `mdat` (Fast-Start) and writes the complete `.mp4`.
    pub fn finish(&mut self) -> Result<MuxSummary, StudioError> {
        if self.finished {
            return Err(StudioError::MuxError {
                detail: "Muxer already finished".into(),
            });
        }
        if self.video_samples.is_empty() {
            self.cleanup_temp();
            return Err(StudioError::MuxError {
                detail: "Cannot finalize MP4 with zero video frames".into(),
            });
        }
        if self.sps_list.is_empty() || self.pps_list.is_empty() {
            self.cleanup_temp();
            return Err(StudioError::MuxError {
                detail: "H.264 SPS/PPS never received before finish()".into(),
            });
        }

        if let Some(mut writer) = self.temp_writer.take() {
            writer.flush().map_err(|e| StudioError::IoError {
                detail: format!("Failed flushing temp mdat: {e}"),
            })?;
        }

        // Compute track durations & validate A/V PTS drift (trim trailing audio > 1 video frame).
        let video_ticks_total: u64 = self.video_samples.iter().map(|s| s.duration_ticks as u64).sum();
        let video_duration_us =
            (video_ticks_total * 1_000_000) / self.video_timescale.max(1) as u64;
        let one_frame_us = 1_000_000 / self.fps.max(1) as u64;

        let include_audio = self.has_audio
            && !self.audio_samples.is_empty()
            && !self.audio_specific_config.is_empty();

        let mut av_drift_ms: i64 = 0;
        if include_audio {
            let max_audio_ticks = ((video_duration_us + one_frame_us) as u128
                * self.audio_sample_rate as u128)
                / 1_000_000;
            let mut acc_ticks: u128 = 0;
            let mut keep_count = 0usize;
            for s in &self.audio_samples {
                if acc_ticks + s.duration_ticks as u128 <= max_audio_ticks || keep_count == 0 {
                    acc_ticks += s.duration_ticks as u128;
                    keep_count += 1;
                } else {
                    break;
                }
            }
            self.audio_samples.truncate(keep_count);
            let audio_duration_us = (acc_ticks * 1_000_000) / self.audio_sample_rate as u128;
            av_drift_ms = (audio_duration_us as i64 - video_duration_us as i64) / 1000;
        }

        let ftyp = build_ftyp();
        // Pass 1: build moov with 0 base offset to obtain its exact byte size.
        let moov_pass1 = self.build_moov(0, include_audio)?;
        let mdat_data_offset = (ftyp.len() + moov_pass1.len() + 8) as u32;
        // Pass 2: build moov with real mdat data offset patched into stco.
        let moov = self.build_moov(mdat_data_offset, include_audio)?;
        debug_assert_eq!(moov.len(), moov_pass1.len());

        let total_mdat_box_size = u32::try_from(8 + self.mdat_bytes_written).map_err(|_| {
            StudioError::MuxError {
                detail: "MP4 mdat exceeds 4 GiB stco limit".into(),
            }
        })?;

        let out_file = File::create(&self.output_path).map_err(|e| StudioError::IoError {
            detail: format!("Failed creating final MP4: {e}"),
        })?;
        let mut out = BufWriter::with_capacity(256 * 1024, out_file);
        out.write_all(&ftyp)?;
        out.write_all(&moov)?;
        out.write_all(&total_mdat_box_size.to_be_bytes())?;
        out.write_all(b"mdat")?;

        let temp_file = File::open(&self.temp_mdat_path)?;
        let mut reader = BufReader::with_capacity(256 * 1024, temp_file);
        let mut copy_buf = vec![0u8; 256 * 1024];
        loop {
            let n = reader.read(&mut copy_buf)?;
            if n == 0 {
                break;
            }
            out.write_all(&copy_buf[..n])?;
        }
        out.flush()?;
        drop(out);
        drop(reader);

        self.finished = true;
        self.cleanup_temp();

        let file_size_bytes = fs::metadata(&self.output_path)?.len();
        Ok(MuxSummary {
            output_path: self.output_path.to_string_lossy().into_owned(),
            video_frames: self.video_samples.len() as u64,
            audio_frames: if include_audio {
                self.audio_samples.len() as u64
            } else {
                0
            },
            duration_ms: video_duration_us / 1000,
            file_size_bytes,
            av_drift_ms,
        })
    }

    /// Aborts muxing and deletes temporary payload files.
    pub fn abort(&mut self) {
        self.temp_writer.take();
        self.finished = true;
        self.cleanup_temp();
    }

    fn cleanup_temp(&self) {
        let _ = fs::remove_file(&self.temp_mdat_path);
    }

    fn ensure_stco_fits(&self, projected_rel_end: u64) -> Result<(), StudioError> {
        // Reserve 64 MiB headroom for ftyp + moov so stco 32-bit offsets never overflow.
        if projected_rel_end > (u32::MAX as u64 - 64 * 1024 * 1024) {
            return Err(StudioError::MuxError {
                detail: "Output exceeds 4 GiB stco MP4 limit".into(),
            });
        }
        Ok(())
    }

    fn ingest_config_nals(&mut self, bytes: &[u8]) {
        for nal in split_nals_auto(bytes) {
            if nal.is_empty() {
                continue;
            }
            match nal[0] & 0x1F {
                7 => push_unique(&mut self.sps_list, nal),
                8 => push_unique(&mut self.pps_list, nal),
                _ => {}
            }
        }
    }

    fn build_moov(&self, mdat_data_offset: u32, include_audio: bool) -> Result<Vec<u8>, StudioError> {
        let movie_timescale: u32 = 1000;
        let video_ticks_total: u64 = self.video_samples.iter().map(|s| s.duration_ticks as u64).sum();
        let movie_duration_ms = ((video_ticks_total * movie_timescale as u64)
            / self.video_timescale.max(1) as u64) as u32;

        let mvhd = build_mvhd(
            movie_timescale,
            movie_duration_ms,
            if include_audio { 3 } else { 2 },
        );
        let video_trak = self.build_video_trak(movie_timescale, movie_duration_ms, mdat_data_offset)?;
        let mut moov_body = Vec::with_capacity(mvhd.len() + video_trak.len() + 4096);
        moov_body.extend_from_slice(&mvhd);
        moov_body.extend_from_slice(&video_trak);

        if include_audio {
            let audio_ticks_total: u64 =
                self.audio_samples.iter().map(|s| s.duration_ticks as u64).sum();
            let audio_duration_ms = ((audio_ticks_total * movie_timescale as u64)
                / self.audio_sample_rate.max(1) as u64) as u32;
            let audio_trak =
                self.build_audio_trak(movie_timescale, audio_duration_ms, mdat_data_offset)?;
            moov_body.extend_from_slice(&audio_trak);
        }

        Ok(wrap_box(b"moov", &moov_body))
    }

    fn build_video_trak(
        &self,
        _movie_timescale: u32,
        movie_duration_ms: u32,
        mdat_data_offset: u32,
    ) -> Result<Vec<u8>, StudioError> {
        let tkhd = build_tkhd(1, movie_duration_ms, self.width, self.height, false);
        let video_ticks_total: u32 = self
            .video_samples
            .iter()
            .map(|s| s.duration_ticks as u64)
            .sum::<u64>() as u32;
        let mdhd = build_mdhd(self.video_timescale, video_ticks_total);
        let hdlr = build_hdlr(b"vide", "VideoHandler");
        let vmhd = build_vmhd();
        let dinf = build_dinf();

        let avc1 = build_avc1(self.width, self.height, &self.sps_list, &self.pps_list);
        let stsd = build_stsd(&avc1);
        let stts = build_stts(&self.video_samples);
        let stss = build_stss(&self.video_samples);
        let (stsc, stco) = build_chunks_and_offsets(&self.video_samples, mdat_data_offset)?;
        let stsz = build_stsz(&self.video_samples);

        let mut stbl_body = Vec::new();
        stbl_body.extend_from_slice(&stsd);
        stbl_body.extend_from_slice(&stts);
        stbl_body.extend_from_slice(&stss);
        stbl_body.extend_from_slice(&stsc);
        stbl_body.extend_from_slice(&stsz);
        stbl_body.extend_from_slice(&stco);
        let stbl = wrap_box(b"stbl", &stbl_body);

        let mut minf_body = Vec::new();
        minf_body.extend_from_slice(&vmhd);
        minf_body.extend_from_slice(&dinf);
        minf_body.extend_from_slice(&stbl);
        let minf = wrap_box(b"minf", &minf_body);

        let mut mdia_body = Vec::new();
        mdia_body.extend_from_slice(&mdhd);
        mdia_body.extend_from_slice(&hdlr);
        mdia_body.extend_from_slice(&minf);
        let mdia = wrap_box(b"mdia", &mdia_body);

        let mut trak_body = Vec::new();
        trak_body.extend_from_slice(&tkhd);
        trak_body.extend_from_slice(&mdia);
        Ok(wrap_box(b"trak", &trak_body))
    }

    fn build_audio_trak(
        &self,
        _movie_timescale: u32,
        audio_duration_ms: u32,
        mdat_data_offset: u32,
    ) -> Result<Vec<u8>, StudioError> {
        let tkhd = build_tkhd(2, audio_duration_ms, 0, 0, true);
        let audio_ticks_total: u32 = self
            .audio_samples
            .iter()
            .map(|s| s.duration_ticks as u64)
            .sum::<u64>() as u32;
        let mdhd = build_mdhd(self.audio_sample_rate, audio_ticks_total);
        let hdlr = build_hdlr(b"soun", "SoundHandler");
        let smhd = build_smhd();
        let dinf = build_dinf();

        let mp4a = build_mp4a(
            self.audio_sample_rate,
            self.audio_channels,
            &self.audio_specific_config,
        );
        let stsd = build_stsd(&mp4a);
        let stts = build_stts(&self.audio_samples);
        let (stsc, stco) = build_chunks_and_offsets(&self.audio_samples, mdat_data_offset)?;
        let stsz = build_stsz(&self.audio_samples);

        let mut stbl_body = Vec::new();
        stbl_body.extend_from_slice(&stsd);
        stbl_body.extend_from_slice(&stts);
        stbl_body.extend_from_slice(&stsc);
        stbl_body.extend_from_slice(&stsz);
        stbl_body.extend_from_slice(&stco);
        let stbl = wrap_box(b"stbl", &stbl_body);

        let mut minf_body = Vec::new();
        minf_body.extend_from_slice(&smhd);
        minf_body.extend_from_slice(&dinf);
        minf_body.extend_from_slice(&stbl);
        let minf = wrap_box(b"minf", &minf_body);

        let mut mdia_body = Vec::new();
        mdia_body.extend_from_slice(&mdhd);
        mdia_body.extend_from_slice(&hdlr);
        mdia_body.extend_from_slice(&minf);
        let mdia = wrap_box(b"mdia", &mdia_body);

        let mut trak_body = Vec::new();
        trak_body.extend_from_slice(&tkhd);
        trak_body.extend_from_slice(&mdia);
        Ok(wrap_box(b"trak", &trak_body))
    }
}

impl Drop for Mp4Muxer {
    fn drop(&mut self) {
        if !self.finished {
            self.temp_writer.take();
            self.cleanup_temp();
        }
    }
}

fn push_unique(list: &mut Vec<Vec<u8>>, nal: &[u8]) {
    if !list.iter().any(|existing| existing.as_slice() == nal) {
        list.push(nal.to_vec());
    }
}

/// Splits Annex-B (`0x00000001` or `0x000001`) into raw NAL slices.
/// If no Annex-B start code is present at the start, returns the slice directly.
pub fn split_nals_auto(data: &[u8]) -> Vec<&[u8]> {
    let starts_with_annex_b = data.starts_with(&[0, 0, 0, 1]) || data.starts_with(&[0, 0, 1]);
    if !starts_with_annex_b {
        return if data.is_empty() { vec![] } else { vec![data] };
    }

    let mut positions: Vec<(usize, usize)> = Vec::new();
    let mut i = 0usize;
    while i + 3 <= data.len() {
        if i + 4 <= data.len() && data[i..i + 4] == [0, 0, 0, 1] {
            positions.push((i, i + 4));
            i += 4;
        } else if data[i..i + 3] == [0, 0, 1] {
            positions.push((i, i + 3));
            i += 3;
        } else {
            i += 1;
        }
    }

    let mut out = Vec::with_capacity(positions.len());
    for idx in 0..positions.len() {
        let start = positions[idx].1;
        let end = if idx + 1 < positions.len() {
            positions[idx + 1].0
        } else {
            data.len()
        };
        if start < end {
            out.push(&data[start..end]);
        }
    }
    out
}

fn wrap_box(tag: &[u8; 4], payload: &[u8]) -> Vec<u8> {
    let total = (8 + payload.len()) as u32;
    let mut out = Vec::with_capacity(total as usize);
    out.extend_from_slice(&total.to_be_bytes());
    out.extend_from_slice(tag);
    out.extend_from_slice(payload);
    out
}

fn wrap_full_box(tag: &[u8; 4], version: u8, flags: u32, payload: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(4 + payload.len());
    body.push(version);
    body.extend_from_slice(&flags.to_be_bytes()[1..4]);
    body.extend_from_slice(payload);
    wrap_box(tag, &body)
}

fn build_ftyp() -> Vec<u8> {
    let mut p = Vec::with_capacity(24);
    p.extend_from_slice(b"isom");
    p.extend_from_slice(&512u32.to_be_bytes());
    p.extend_from_slice(b"isom");
    p.extend_from_slice(b"iso2");
    p.extend_from_slice(b"avc1");
    p.extend_from_slice(b"mp41");
    wrap_box(b"ftyp", &p)
}

fn build_mvhd(timescale: u32, duration: u32, next_track_id: u32) -> Vec<u8> {
    let mut p = Vec::with_capacity(96);
    p.extend_from_slice(&0u32.to_be_bytes()); // creation_time
    p.extend_from_slice(&0u32.to_be_bytes()); // modification_time
    p.extend_from_slice(&timescale.to_be_bytes());
    p.extend_from_slice(&duration.to_be_bytes());
    p.extend_from_slice(&0x0001_0000u32.to_be_bytes()); // rate 1.0
    p.extend_from_slice(&0x0100u16.to_be_bytes()); // volume 1.0
    p.extend_from_slice(&[0u8; 10]); // reserved
    write_identity_matrix(&mut p);
    p.extend_from_slice(&[0u8; 24]); // pre_defined
    p.extend_from_slice(&next_track_id.to_be_bytes());
    wrap_full_box(b"mvhd", 0, 0, &p)
}

fn build_tkhd(track_id: u32, duration: u32, width: u32, height: u32, is_audio: bool) -> Vec<u8> {
    let mut p = Vec::with_capacity(80);
    p.extend_from_slice(&0u32.to_be_bytes()); // creation
    p.extend_from_slice(&0u32.to_be_bytes()); // modification
    p.extend_from_slice(&track_id.to_be_bytes());
    p.extend_from_slice(&0u32.to_be_bytes()); // reserved
    p.extend_from_slice(&duration.to_be_bytes());
    p.extend_from_slice(&[0u8; 8]); // reserved
    p.extend_from_slice(&0u16.to_be_bytes()); // layer
    p.extend_from_slice(&(if is_audio { 1u16 } else { 0u16 }).to_be_bytes()); // alternate_group
    p.extend_from_slice(&(if is_audio { 0x0100u16 } else { 0u16 }).to_be_bytes()); // volume
    p.extend_from_slice(&0u16.to_be_bytes()); // reserved
    write_identity_matrix(&mut p);
    p.extend_from_slice(&(width << 16).to_be_bytes()); // 16.16 fixed
    p.extend_from_slice(&(height << 16).to_be_bytes());
    wrap_full_box(b"tkhd", 0, 0x000003, &p) // enabled + in_movie
}

fn build_mdhd(timescale: u32, duration: u32) -> Vec<u8> {
    let mut p = Vec::with_capacity(20);
    p.extend_from_slice(&0u32.to_be_bytes());
    p.extend_from_slice(&0u32.to_be_bytes());
    p.extend_from_slice(&timescale.to_be_bytes());
    p.extend_from_slice(&duration.to_be_bytes());
    p.extend_from_slice(&0x55C4u16.to_be_bytes()); // "und"
    p.extend_from_slice(&0u16.to_be_bytes());
    wrap_full_box(b"mdhd", 0, 0, &p)
}

fn build_hdlr(handler_type: &[u8; 4], name: &str) -> Vec<u8> {
    let mut p = Vec::with_capacity(24 + name.len());
    p.extend_from_slice(&0u32.to_be_bytes());
    p.extend_from_slice(handler_type);
    p.extend_from_slice(&[0u8; 12]);
    p.extend_from_slice(name.as_bytes());
    p.push(0);
    wrap_full_box(b"hdlr", 0, 0, &p)
}

fn build_vmhd() -> Vec<u8> {
    wrap_full_box(b"vmhd", 0, 1, &[0u8; 8])
}

fn build_smhd() -> Vec<u8> {
    wrap_full_box(b"smhd", 0, 0, &[0u8; 4])
}

fn build_dinf() -> Vec<u8> {
    let url = wrap_full_box(b"url ", 0, 1, &[]);
    let mut dref_p = Vec::with_capacity(4 + url.len());
    dref_p.extend_from_slice(&1u32.to_be_bytes());
    dref_p.extend_from_slice(&url);
    let dref = wrap_full_box(b"dref", 0, 0, &dref_p);
    wrap_box(b"dinf", &dref)
}

fn build_stsd(entry: &[u8]) -> Vec<u8> {
    let mut p = Vec::with_capacity(4 + entry.len());
    p.extend_from_slice(&1u32.to_be_bytes());
    p.extend_from_slice(entry);
    wrap_full_box(b"stsd", 0, 0, &p)
}

fn build_avc1(width: u32, height: u32, sps_list: &[Vec<u8>], pps_list: &[Vec<u8>]) -> Vec<u8> {
    let mut p = Vec::with_capacity(160);
    p.extend_from_slice(&[0u8; 6]); // reserved
    p.extend_from_slice(&1u16.to_be_bytes()); // data_reference_index
    p.extend_from_slice(&[0u8; 16]); // pre_defined + reserved
    p.extend_from_slice(&(width as u16).to_be_bytes());
    p.extend_from_slice(&(height as u16).to_be_bytes());
    p.extend_from_slice(&0x0048_0000u32.to_be_bytes()); // 72 dpi
    p.extend_from_slice(&0x0048_0000u32.to_be_bytes()); // 72 dpi
    p.extend_from_slice(&0u32.to_be_bytes()); // reserved
    p.extend_from_slice(&1u16.to_be_bytes()); // frame_count
    p.extend_from_slice(&[0u8; 32]); // compressorname
    p.extend_from_slice(&0x0018u16.to_be_bytes()); // depth = 24
    p.extend_from_slice(&(-1i16).to_be_bytes()); // pre_defined

    // avcC box
    let sps0 = &sps_list[0];
    let profile = *sps0.get(1).unwrap_or(&0x64);
    let compat = *sps0.get(2).unwrap_or(&0x00);
    let level = *sps0.get(3).unwrap_or(&0x28);
    let mut avcc = Vec::new();
    avcc.push(1); // configurationVersion
    avcc.push(profile);
    avcc.push(compat);
    avcc.push(level);
    avcc.push(0xFF); // 4-byte NALU lengthSizeMinusOne = 3
    avcc.push(0xE0 | (sps_list.len().min(31) as u8));
    for sps in sps_list.iter().take(31) {
        avcc.extend_from_slice(&(sps.len() as u16).to_be_bytes());
        avcc.extend_from_slice(sps);
    }
    avcc.push(pps_list.len().min(255) as u8);
    for pps in pps_list.iter().take(255) {
        avcc.extend_from_slice(&(pps.len() as u16).to_be_bytes());
        avcc.extend_from_slice(pps);
    }
    p.extend_from_slice(&wrap_box(b"avcC", &avcc));

    // colr (nclx BT.709 / sRGB)
    let mut colr = Vec::with_capacity(11);
    colr.extend_from_slice(b"nclx");
    colr.extend_from_slice(&1u16.to_be_bytes()); // BT.709 primaries
    colr.extend_from_slice(&1u16.to_be_bytes()); // BT.709 transfer
    colr.extend_from_slice(&1u16.to_be_bytes()); // BT.709 matrix
    colr.push(0); // video range
    p.extend_from_slice(&wrap_box(b"colr", &colr));

    // pasp (1:1 square pixels)
    let mut pasp = Vec::with_capacity(8);
    pasp.extend_from_slice(&1u32.to_be_bytes());
    pasp.extend_from_slice(&1u32.to_be_bytes());
    p.extend_from_slice(&wrap_box(b"pasp", &pasp));

    wrap_box(b"avc1", &p)
}

fn build_mp4a(sample_rate: u32, channels: u16, asc: &[u8]) -> Vec<u8> {
    let mut p = Vec::with_capacity(96);
    p.extend_from_slice(&[0u8; 6]); // reserved
    p.extend_from_slice(&1u16.to_be_bytes()); // data_reference_index
    p.extend_from_slice(&[0u8; 8]); // reserved
    p.extend_from_slice(&channels.to_be_bytes());
    p.extend_from_slice(&16u16.to_be_bytes()); // sample size
    p.extend_from_slice(&0u16.to_be_bytes()); // pre_defined
    p.extend_from_slice(&0u16.to_be_bytes()); // reserved
    p.extend_from_slice(&(sample_rate << 16).to_be_bytes());

    let esds = build_esds(asc);
    p.extend_from_slice(&esds);
    wrap_box(b"mp4a", &p)
}

fn build_esds(asc: &[u8]) -> Vec<u8> {
    let dec_spec_len = asc.len();
    let dec_cfg_len = 13 + 2 + dec_spec_len;
    let sl_cfg_len = 1;
    let es_desc_len = 3 + (2 + dec_cfg_len) + (2 + sl_cfg_len);

    let mut p = Vec::with_capacity(es_desc_len + 2);
    // ES_Descriptor (tag 0x03)
    p.push(0x03);
    p.push(es_desc_len as u8);
    p.extend_from_slice(&2u16.to_be_bytes()); // ES_ID
    p.push(0x00); // flags

    // DecoderConfigDescriptor (tag 0x04)
    p.push(0x04);
    p.push(dec_cfg_len as u8);
    p.push(0x40); // Audio ISO/IEC 14496-3 (AAC)
    p.push(0x15); // streamType = AudioStream (0x05 << 2) | 1
    p.extend_from_slice(&[0x00, 0x18, 0x00]); // bufferSizeDB
    p.extend_from_slice(&192_000u32.to_be_bytes()); // maxBitrate
    p.extend_from_slice(&192_000u32.to_be_bytes()); // avgBitrate

    // DecoderSpecificInfo (tag 0x05)
    p.push(0x05);
    p.push(dec_spec_len as u8);
    p.extend_from_slice(asc);

    // SLConfigDescriptor (tag 0x06)
    p.push(0x06);
    p.push(0x01);
    p.push(0x02);

    wrap_full_box(b"esds", 0, 0, &p)
}

fn build_stts(samples: &[SampleRecord]) -> Vec<u8> {
    let mut runs: Vec<(u32, u32)> = Vec::new();
    for s in samples {
        if let Some(last) = runs.last_mut() {
            if last.1 == s.duration_ticks {
                last.0 += 1;
                continue;
            }
        }
        runs.push((1, s.duration_ticks));
    }
    let mut p = Vec::with_capacity(4 + runs.len() * 8);
    p.extend_from_slice(&(runs.len() as u32).to_be_bytes());
    for (count, delta) in runs {
        p.extend_from_slice(&count.to_be_bytes());
        p.extend_from_slice(&delta.to_be_bytes());
    }
    wrap_full_box(b"stts", 0, 0, &p)
}

fn build_stss(samples: &[SampleRecord]) -> Vec<u8> {
    let sync: Vec<u32> = samples
        .iter()
        .enumerate()
        .filter_map(|(idx, s)| if s.is_keyframe { Some((idx + 1) as u32) } else { None })
        .collect();
    let mut p = Vec::with_capacity(4 + sync.len() * 4);
    p.extend_from_slice(&(sync.len() as u32).to_be_bytes());
    for idx in sync {
        p.extend_from_slice(&idx.to_be_bytes());
    }
    wrap_full_box(b"stss", 0, 0, &p)
}

fn build_stsz(samples: &[SampleRecord]) -> Vec<u8> {
    let mut p = Vec::with_capacity(8 + samples.len() * 4);
    p.extend_from_slice(&0u32.to_be_bytes()); // variable sample sizes
    p.extend_from_slice(&(samples.len() as u32).to_be_bytes());
    for s in samples {
        p.extend_from_slice(&s.size.to_be_bytes());
    }
    wrap_full_box(b"stsz", 0, 0, &p)
}

fn build_chunks_and_offsets(
    samples: &[SampleRecord],
    mdat_data_offset: u32,
) -> Result<(Vec<u8>, Vec<u8>), StudioError> {
    // Group contiguous same-track samples in mdat into single chunks to keep stco compact.
    struct Chunk {
        first_chunk: u32,
        samples_per_chunk: u32,
        offset: u32,
    }
    let mut chunks: Vec<Chunk> = Vec::new();
    let mut expected_next_rel: Option<u64> = None;

    for s in samples {
        if let (Some(prev_end), Some(last)) = (expected_next_rel, chunks.last_mut()) {
            if prev_end == s.mdat_rel_offset {
                last.samples_per_chunk += 1;
                expected_next_rel = Some(s.mdat_rel_offset + s.size as u64);
                continue;
            }
        }
        let chunk_idx = (chunks.len() + 1) as u32;
        let abs_offset = mdat_data_offset as u64 + s.mdat_rel_offset;
        let abs_u32 = u32::try_from(abs_offset).map_err(|_| StudioError::MuxError {
            detail: "Chunk offset overflowed 32-bit stco".into(),
        })?;
        chunks.push(Chunk {
            first_chunk: chunk_idx,
            samples_per_chunk: 1,
            offset: abs_u32,
        });
        expected_next_rel = Some(s.mdat_rel_offset + s.size as u64);
    }

    // Compact stsc entries where samples_per_chunk stays unchanged.
    let mut stsc_entries: Vec<(u32, u32, u32)> = Vec::new();
    for ch in &chunks {
        if let Some(last) = stsc_entries.last() {
            if last.1 == ch.samples_per_chunk {
                continue;
            }
        }
        stsc_entries.push((ch.first_chunk, ch.samples_per_chunk, 1));
    }

    let mut stsc_p = Vec::with_capacity(4 + stsc_entries.len() * 12);
    stsc_p.extend_from_slice(&(stsc_entries.len() as u32).to_be_bytes());
    for (first, spc, sdi) in stsc_entries {
        stsc_p.extend_from_slice(&first.to_be_bytes());
        stsc_p.extend_from_slice(&spc.to_be_bytes());
        stsc_p.extend_from_slice(&sdi.to_be_bytes());
    }

    let mut stco_p = Vec::with_capacity(4 + chunks.len() * 4);
    stco_p.extend_from_slice(&(chunks.len() as u32).to_be_bytes());
    for ch in chunks {
        stco_p.extend_from_slice(&ch.offset.to_be_bytes());
    }

    Ok((wrap_full_box(b"stsc", 0, 0, &stsc_p), wrap_full_box(b"stco", 0, 0, &stco_p)))
}

fn write_identity_matrix(buf: &mut Vec<u8>) {
    let vals: [u32; 9] = [0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000];
    for v in vals {
        buf.extend_from_slice(&v.to_be_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn top_level_boxes(bytes: &[u8]) -> Vec<([u8; 4], usize, usize)> {
        let mut out = Vec::new();
        let mut pos = 0usize;
        while pos + 8 <= bytes.len() {
            let size = u32::from_be_bytes(bytes[pos..pos + 4].try_into().unwrap()) as usize;
            let tag: [u8; 4] = bytes[pos + 4..pos + 8].try_into().unwrap();
            out.push((tag, pos, size));
            pos += size;
        }
        assert_eq!(pos, bytes.len(), "boxes did not sum to file length");
        out
    }

    #[test]
    fn fast_start_places_moov_before_mdat_and_splits_annex_b() {
        let dir = tempdir().unwrap();
        let out_mp4 = dir.path().join("test_faststart.mp4");
        let mut muxer = Mp4Muxer::new(&out_mp4, 1080, 1920, 30, true, 48_000, 2).unwrap();

        // Concatenated SPS (type 7) + PPS (type 8) in csd-0, csd-1 = None
        let csd0 = [
            0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, // SPS
            0, 0, 1, 0x68, 0xEB, 0xE3, 0xCB, 0x22, // PPS (3-byte start code)
        ];
        muxer.set_video_config(&csd0, None).unwrap();
        muxer.set_audio_config(&[0x11, 0x90]).unwrap();

        // Keyframe with inline SPS + IDR slice
        let frame0 = [
            0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9,
            0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21, 0xA0,
        ];
        let frame1 = [0, 0, 0, 1, 0x41, 0x9A, 0x11, 0x22];
        muxer.write_video_sample(&frame0, 0, true).unwrap();
        muxer.write_audio_sample(&[0x21, 0x00, 0x49, 0x90], 0).unwrap();
        muxer.write_video_sample(&frame1, 33_333, false).unwrap();
        muxer.write_audio_sample(&[0x21, 0x00, 0x49, 0x91], 21_333).unwrap();

        let summary = muxer.finish().unwrap();
        assert_eq!(summary.video_frames, 2);
        assert_eq!(summary.audio_frames, 2);
        assert!(!out_mp4.with_extension("mp4.mdat.tmp").exists());

        let bytes = fs::read(&out_mp4).unwrap();
        let boxes = top_level_boxes(&bytes);
        assert_eq!(boxes.len(), 3);
        assert_eq!(&boxes[0].0, b"ftyp");
        assert_eq!(&boxes[1].0, b"moov");
        assert_eq!(&boxes[2].0, b"mdat");

        // Verify first mdat payload bytes match AVCC 4-byte length prefix of IDR slice (len = 5)
        let mdat_payload_start = boxes[2].1 + 8;
        assert_eq!(
            &bytes[mdat_payload_start..mdat_payload_start + 9],
            &[0, 0, 0, 5, 0x65, 0x88, 0x84, 0x21, 0xA0]
        );
    }

    #[test]
    fn video_only_export_succeeds_without_audio() {
        let dir = tempdir().unwrap();
        let out_mp4 = dir.path().join("video_only.mp4");
        let mut muxer = Mp4Muxer::new(&out_mp4, 720, 1280, 30, false, 48_000, 2).unwrap();
        muxer.set_video_config(&[0x67, 0x42, 0x00, 0x1E], Some(&[0x68, 0xCE, 0x3C])).unwrap();
        muxer.write_video_sample(&[0, 0, 0, 1, 0x65, 0x11, 0x22], 0, true).unwrap();
        let summary = muxer.finish().unwrap();
        assert_eq!(summary.video_frames, 1);
        assert_eq!(summary.audio_frames, 0);
        let bytes = fs::read(&out_mp4).unwrap();
        let boxes = top_level_boxes(&bytes);
        assert_eq!(&boxes[1].0, b"moov");
        assert_eq!(&boxes[2].0, b"mdat");
    }
}
