//! HTML2Reel Studio Rust Native Core exposed to Android Kotlin via Mozilla UniFFI.

pub mod audio_engine;
pub mod html_shim;
pub mod muxer;

use std::sync::{Arc, Mutex};

pub use audio_engine::{AudioMixConfig, AudioMixSession};
pub use html_shim::{HtmlMetadata, PreprocessOptions, PreprocessedHtml};
pub use muxer::{Mp4Muxer, MuxSummary};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum StudioError {
    #[error("I/O error: {detail}")]
    IoError { detail: String },
    #[error("Audio processing error: {detail}")]
    AudioError { detail: String },
    #[error("MP4 muxing error: {detail}")]
    MuxError { detail: String },
}

impl From<std::io::Error> for StudioError {
    fn from(err: std::io::Error) -> Self {
        StudioError::IoError {
            detail: err.to_string(),
        }
    }
}

/// Preprocesses raw HTML, extracts metadata, and injects offline font CSS + deterministic clock shim.
#[uniffi::export]
pub fn preprocess_html_document(raw_html: String, options: PreprocessOptions) -> PreprocessedHtml {
    html_shim::preprocess_html(&raw_html, &options)
}

/// Extracts metadata tags (`video-duration`, `video-resolution`, `video-fps`, `<title>`) without modifying HTML.
#[uniffi::export]
pub fn extract_html_metadata(raw_html: String) -> HtmlMetadata {
    html_shim::extract_metadata(&raw_html)
}

/// UniFFI-safe wrapper around `Mp4Muxer`.
#[derive(uniffi::Object)]
pub struct Mp4MuxerSession {
    inner: Mutex<Option<Mp4Muxer>>,
}

#[uniffi::export]
impl Mp4MuxerSession {
    #[uniffi::constructor]
    pub fn new(
        output_path: String,
        width: u32,
        height: u32,
        fps: u32,
        has_audio: bool,
        audio_sample_rate: u32,
        audio_channels: u16,
    ) -> Result<Arc<Self>, StudioError> {
        let muxer = Mp4Muxer::new(
            output_path,
            width,
            height,
            fps,
            has_audio,
            audio_sample_rate,
            audio_channels,
        )?;
        Ok(Arc::new(Self {
            inner: Mutex::new(Some(muxer)),
        }))
    }

    pub fn set_video_config(&self, csd0: Vec<u8>, csd1: Option<Vec<u8>>) -> Result<(), StudioError> {
        let mut guard = self.inner.lock().map_err(|_| StudioError::MuxError {
            detail: "Muxer lock poisoned".into(),
        })?;
        let muxer = guard.as_mut().ok_or_else(|| StudioError::MuxError {
            detail: "Muxer already closed".into(),
        })?;
        muxer.set_video_config(&csd0, csd1.as_deref())
    }

    pub fn set_audio_config(&self, csd0: Vec<u8>) -> Result<(), StudioError> {
        let mut guard = self.inner.lock().map_err(|_| StudioError::MuxError {
            detail: "Muxer lock poisoned".into(),
        })?;
        let muxer = guard.as_mut().ok_or_else(|| StudioError::MuxError {
            detail: "Muxer already closed".into(),
        })?;
        muxer.set_audio_config(&csd0)
    }

    pub fn write_video_sample(
        &self,
        data: Vec<u8>,
        pts_us: u64,
        is_keyframe: bool,
    ) -> Result<(), StudioError> {
        let mut guard = self.inner.lock().map_err(|_| StudioError::MuxError {
            detail: "Muxer lock poisoned".into(),
        })?;
        let muxer = guard.as_mut().ok_or_else(|| StudioError::MuxError {
            detail: "Muxer already closed".into(),
        })?;
        muxer.write_video_sample(&data, pts_us, is_keyframe)
    }

    pub fn write_audio_sample(&self, data: Vec<u8>, pts_us: u64) -> Result<(), StudioError> {
        let mut guard = self.inner.lock().map_err(|_| StudioError::MuxError {
            detail: "Muxer lock poisoned".into(),
        })?;
        let muxer = guard.as_mut().ok_or_else(|| StudioError::MuxError {
            detail: "Muxer already closed".into(),
        })?;
        muxer.write_audio_sample(&data, pts_us)
    }

    pub fn finish(&self) -> Result<MuxSummary, StudioError> {
        let mut guard = self.inner.lock().map_err(|_| StudioError::MuxError {
            detail: "Muxer lock poisoned".into(),
        })?;
        let mut muxer = guard.take().ok_or_else(|| StudioError::MuxError {
            detail: "Muxer already finished or aborted".into(),
        })?;
        muxer.finish()
    }

    pub fn abort(&self) {
        if let Ok(mut guard) = self.inner.lock() {
            if let Some(mut muxer) = guard.take() {
                muxer.abort();
            }
        }
    }
}
