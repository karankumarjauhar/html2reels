//! Deterministic clock shim injector, offline font/HUD CSS injector, and metadata parser.

use serde::{Deserialize, Serialize};

/// Extracted metadata tags discovered inside user HTML documents.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct HtmlMetadata {
    pub title: Option<String>,
    pub duration_sec: Option<f64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub fps: Option<u32>,
    pub has_explicit_seek_hook: bool,
}

/// Options controlling HTML preprocessing and deterministic shim injection.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct PreprocessOptions {
    pub export_mode: bool,
    pub auto_hide_hud: bool,
    pub inject_offline_fonts: bool,
    pub font_base_url: String,
}

/// Result of preprocessing raw HTML before loading into WebView.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct PreprocessedHtml {
    pub html: String,
    pub metadata: HtmlMetadata,
}

/// Parses HTML metadata tags and injects offline fonts, optional HUD hiding CSS,
/// and the `window.__HTML2REEL__` deterministic clock controller right before the first `<script>`.
pub fn preprocess_html(raw_html: &str, options: &PreprocessOptions) -> PreprocessedHtml {
    let metadata = extract_metadata(raw_html);
    let mut injection = String::with_capacity(4096);

    if options.inject_offline_fonts {
        let base = options.font_base_url.trim_end_matches('/');
        injection.push_str(&build_offline_font_style(base));
    }

    if options.export_mode && options.auto_hide_hud {
        injection.push_str(
            "<style data-html2reel-hud>\
             .hud, #hud, .controls, #controls, [data-export-hide] { \
             display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; \
             }\
             </style>",
        );
    }

    injection.push_str(&build_clock_shim(options.export_mode, options.auto_hide_hud));

    let html = inject_before_first_script(raw_html, &injection);
    PreprocessedHtml { html, metadata }
}

/// Extracts title, video-duration, video-resolution, video-fps, and explicit seekTo hooks.
pub fn extract_metadata(html: &str) -> HtmlMetadata {
    let title = extract_title(html);
    let mut duration_sec: Option<f64> = None;
    let mut width: Option<u32> = None;
    let mut height: Option<u32> = None;
    let mut fps: Option<u32> = None;

    let lower = html.to_ascii_lowercase();
    let mut cursor = 0usize;
    while let Some(rel_start) = lower[cursor..].find("<meta") {
        let tag_start = cursor + rel_start;
        let Some(rel_end) = lower[tag_start..].find('>') else {
            break;
        };
        let tag_end = tag_start + rel_end + 1;
        let tag = &html[tag_start..tag_end];

        if let (Some(name), Some(content)) = (
            extract_attr(tag, "name").or_else(|| extract_attr(tag, "property")),
            extract_attr(tag, "content"),
        ) {
            let key = name.trim().to_ascii_lowercase();
            let val = content.trim();
            match key.as_str() {
                "video-duration" | "duration" => {
                    duration_sec = parse_duration_seconds(val);
                }
                "video-resolution" | "resolution" => {
                    if let Some((w, h)) = parse_resolution(val) {
                        width = Some(w);
                        height = Some(h);
                    }
                }
                "video-fps" | "fps" => {
                    if let Ok(parsed) = val.trim_end_matches("fps").trim().parse::<u32>() {
                        if (1..=240).contains(&parsed) {
                            fps = Some(parsed);
                        }
                    }
                }
                _ => {}
            }
        }
        cursor = tag_end;
    }

    let has_explicit_seek_hook = lower.contains("window.seekto")
        || lower.contains("function seekto")
        || lower.contains("window.renderframe")
        || lower.contains("function renderframe");

    HtmlMetadata {
        title,
        duration_sec,
        width,
        height,
        fps,
        has_explicit_seek_hook,
    }
}

fn extract_title(html: &str) -> Option<String> {
    let lower = html.to_ascii_lowercase();
    let start_tag = lower.find("<title")?;
    let open_end = lower[start_tag..].find('>')? + start_tag + 1;
    let close_start = lower[open_end..].find("</title>")? + open_end;
    let text = html[open_end..close_start].trim();
    if text.is_empty() {
        None
    } else {
        Some(text.to_string())
    }
}

fn extract_attr(tag: &str, attr_name: &str) -> Option<String> {
    let lower = tag.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let mut search_from = 0usize;
    while let Some(pos) = lower[search_from..].find(attr_name) {
        let abs = search_from + pos;
        let prev_ok = abs == 0
            || bytes[abs - 1].is_ascii_whitespace()
            || bytes[abs - 1] == b'<';
        let after = abs + attr_name.len();
        if !prev_ok {
            search_from = after;
            continue;
        }
        let rest = tag[after..].trim_start();
        if let Some(after_eq) = rest.strip_prefix('=') {
            let val_part = after_eq.trim_start();
            if let Some(stripped) = val_part.strip_prefix('"') {
                let end = stripped.find('"')?;
                return Some(stripped[..end].to_string());
            } else if let Some(stripped) = val_part.strip_prefix('\'') {
                let end = stripped.find('\'')?;
                return Some(stripped[..end].to_string());
            } else {
                let end = val_part
                    .find(|c: char| c.is_ascii_whitespace() || c == '>' || c == '/')
                    .unwrap_or(val_part.len());
                return Some(val_part[..end].to_string());
            }
        }
        search_from = after;
    }
    None
}

fn parse_duration_seconds(raw: &str) -> Option<f64> {
    let cleaned = raw.trim().trim_end_matches('s').trim();
    let secs = cleaned.parse::<f64>().ok()?;
    if secs.is_finite() && secs > 0.0 && secs <= 3600.0 {
        Some(secs)
    } else {
        None
    }
}

fn parse_resolution(raw: &str) -> Option<(u32, u32)> {
    let normalized = raw
        .trim()
        .to_ascii_lowercase()
        .replace('×', "x")
        .replace(',', "x")
        .replace('*', "x");
    let mut parts = normalized.split('x');
    let w = parts.next()?.trim().parse::<u32>().ok()?;
    let h = parts.next()?.trim().parse::<u32>().ok()?;
    if (128..=4096).contains(&w) && (128..=4096).contains(&h) {
        Some((w, h))
    } else {
        None
    }
}

fn build_offline_font_style(base_url: &str) -> String {
    format!(
        "<style data-html2reel-fonts>\
         @font-face {{ font-family: 'Noto Sans Devanagari'; src: url('{base_url}/NotoSansDevanagari.ttf') format('truetype'); font-weight: 100 900; font-style: normal; font-display: block; }}\
         @font-face {{ font-family: 'Playfair Display'; src: url('{base_url}/PlayfairDisplay.ttf') format('truetype'); font-weight: 100 900; font-style: normal; font-display: block; }}\
         @font-face {{ font-family: 'Playfair Display'; src: url('{base_url}/PlayfairDisplay-Italic.ttf') format('truetype'); font-weight: 100 900; font-style: italic; font-display: block; }}\
         </style>"
    )
}

fn inject_before_first_script(raw_html: &str, snippet: &str) -> String {
    let lower = raw_html.to_ascii_lowercase();
    let insert_idx = lower
        .find("<script")
        .or_else(|| lower.find("</head>"))
        .or_else(|| lower.find("<body").and_then(|b| lower[b..].find('>').map(|g| b + g + 1)))
        .or_else(|| lower.find("<html").and_then(|h| lower[h..].find('>').map(|g| h + g + 1)))
        .unwrap_or(0);

    let mut out = String::with_capacity(raw_html.len() + snippet.len() + 2);
    out.push_str(&raw_html[..insert_idx]);
    out.push_str(snippet);
    out.push_str(&raw_html[insert_idx..]);
    out
}

fn build_clock_shim(export_mode: bool, auto_hide_hud: bool) -> String {
    let export_flag = if export_mode { "true" } else { "false" };
    let hide_hud_flag = if export_mode && auto_hide_hud { "true" } else { "false" };
    format!(
        r#"<script data-html2reel-shim>
(function() {{
  if (window.__HTML2REEL__) return;

  const EXPORT_MODE = {export_flag};
  const AUTO_HIDE_HUD = {hide_hud_flag};

  // Capture native APIs BEFORE monkey-patching so paint barriers never deadlock.
  const nativeRAF = window.requestAnimationFrame.bind(window);
  const nativeCAF = window.cancelAnimationFrame.bind(window);
  const nativePerfNow = (window.performance && window.performance.now)
    ? window.performance.now.bind(window.performance)
    : () => Date.now();
  const NativeDate = window.Date;
  const nativeDateNow = NativeDate.now.bind(NativeDate);
  const nativeSetTimeout = window.setTimeout.bind(window);
  const nativeClearTimeout = window.clearTimeout.bind(window);
  const nativeSetInterval = window.setInterval.bind(window);
  const nativeClearInterval = window.clearInterval.bind(window);

  const BASE_EPOCH_MS = 1700000000000;
  let virtualMs = 0;
  let nextHandle = 1;
  let rafQueue = [];
  let timers = [];

  // MutationObserver-backed animation cache (avoids per-frame O(n) DOM queries).
  let cachedAnimations = [];
  let animationsDirty = true;
  const observer = new MutationObserver(() => {{ animationsDirty = true; }});
  const startObserver = () => {{
    if (document.documentElement) {{
      observer.observe(document.documentElement, {{
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['style', 'class']
      }});
    }}
  }};
  if (document.readyState === 'loading') {{
    document.addEventListener('DOMContentLoaded', startObserver, {{ once: true }});
  }} else {{
    startObserver();
  }}

  function refreshAnimations() {{
    if (animationsDirty && typeof document.getAnimations === 'function') {{
      try {{
        cachedAnimations = document.getAnimations({{ subtree: true }});
        for (let i = 0; i < cachedAnimations.length; i++) {{
          const anim = cachedAnimations[i];
          if (anim.playState === 'running' || anim.playState === 'pending') {{
            anim.pause();
          }}
        }}
        animationsDirty = false;
      }} catch (_) {{}}
    }}
  }}

  function syncDomAndSvgTimelines(seconds, ms) {{
    refreshAnimations();
    for (let i = 0; i < cachedAnimations.length; i++) {{
      try {{
        cachedAnimations[i].pause();
        cachedAnimations[i].currentTime = ms;
      }} catch (_) {{}}
    }}
    const svgs = document.getElementsByTagName('svg');
    for (let i = 0; i < svgs.length; i++) {{
      const svg = svgs[i];
      if (typeof svg.pauseAnimations === 'function') {{
        try {{
          svg.pauseAnimations();
          svg.setCurrentTime(seconds);
        }} catch (_) {{}}
      }}
    }}
  }}

  // Install deterministic clock overrides in export mode OR when explicitly driven via seekTo().
  let clockHijacked = false;
  function enableVirtualClock() {{
    if (clockHijacked) return;
    clockHijacked = true;

    if (window.performance) {{
      try {{
        Object.defineProperty(window.performance, 'now', {{
          configurable: true,
          value: () => virtualMs
        }});
      }} catch (_) {{
        window.performance.now = () => virtualMs;
      }}
    }}

    function VirtualDate(...args) {{
      if (args.length === 0) {{
        return new NativeDate(BASE_EPOCH_MS + virtualMs);
      }}
      return new NativeDate(...args);
    }}
    VirtualDate.prototype = NativeDate.prototype;
    VirtualDate.now = () => BASE_EPOCH_MS + virtualMs;
    VirtualDate.parse = NativeDate.parse;
    VirtualDate.UTC = NativeDate.UTC;
    window.Date = VirtualDate;

    window.requestAnimationFrame = function(cb) {{
      const id = nextHandle++;
      rafQueue.push({{ id, cb }});
      return id;
    }};
    window.cancelAnimationFrame = function(id) {{
      rafQueue = rafQueue.filter(item => item.id !== id);
    }};

    window.setTimeout = function(fn, delay = 0, ...args) {{
      const id = nextHandle++;
      const d = Math.max(0, Number(delay) || 0);
      timers.push({{ id, fn, args, due: virtualMs + d, interval: null }});
      return id;
    }};
    window.clearTimeout = function(id) {{
      timers = timers.filter(t => t.id !== id);
    }};

    window.setInterval = function(fn, delay = 16, ...args) {{
      const id = nextHandle++;
      const d = Math.max(1, Number(delay) || 16);
      timers.push({{ id, fn, args, due: virtualMs + d, interval: d }});
      return id;
    }};
    window.clearInterval = window.clearTimeout;
  }}

  if (EXPORT_MODE) {{
    enableVirtualClock();
  }}

  function flushTimersAndRaf(targetMs) {{
    let guard = 0;
    while (guard++ < 512) {{
      timers.sort((a, b) => a.due - b.due);
      const next = timers[0];
      if (!next || next.due > targetMs) break;
      timers.shift();
      virtualMs = next.due;
      if (next.interval !== null) {{
        timers.push({{
          id: next.id,
          fn: next.fn,
          args: next.args,
          due: next.due + next.interval,
          interval: next.interval
        }});
      }}
      try {{
        if (typeof next.fn === 'function') {{
          next.fn(...next.args);
        }}
      }} catch (_) {{}}
    }}
    virtualMs = targetMs;

    const batch = rafQueue.splice(0, rafQueue.length);
    for (let i = 0; i < batch.length; i++) {{
      try {{
        batch[i].cb(virtualMs);
      }} catch (_) {{}}
    }}
  }}

  function seekTo(seconds) {{
    const s = Math.max(0, Number(seconds) || 0);
    const targetMs = s * 1000.0;
    enableVirtualClock();

    // Priority 1: explicit seekTo / renderFrame author hook.
    if (typeof window.seekTo === 'function') {{
      virtualMs = targetMs;
      window.seekTo(s);
      syncDomAndSvgTimelines(s, targetMs);
      return;
    }}
    if (typeof window.renderFrame === 'function') {{
      virtualMs = targetMs;
      window.renderFrame(s);
      syncDomAndSvgTimelines(s, targetMs);
      return;
    }}

    // Priority 2: step virtual clock monotonically toward targetMs so rAF animations advance cleanly.
    const stepMs = 1000.0 / 60.0;
    if (targetMs < virtualMs) {{
      virtualMs = targetMs;
      flushTimersAndRaf(targetMs);
    }} else {{
      while (virtualMs + stepMs < targetMs) {{
        flushTimersAndRaf(virtualMs + stepMs);
      }}
      flushTimersAndRaf(targetMs);
    }}
    syncDomAndSvgTimelines(s, targetMs);
  }}

  function hideHudIfEnabled() {{
    if (!AUTO_HIDE_HUD) return;
    try {{
      window.dispatchEvent(new KeyboardEvent('keydown', {{ key: 'h', code: 'KeyH', bubbles: true }}));
      document.dispatchEvent(new KeyboardEvent('keydown', {{ key: 'h', code: 'KeyH', bubbles: true }}));
    }} catch (_) {{}}
  }}

  async function waitUntilReady(seq) {{
    try {{
      if (document.fonts && typeof document.fonts.load === 'function') {{
        await Promise.allSettled([
          document.fonts.load('400 24px "Noto Sans Devanagari"'),
          document.fonts.load('700 24px "Noto Sans Devanagari"'),
          document.fonts.load('400 24px "Playfair Display"'),
          document.fonts.load('700 24px "Playfair Display"'),
          document.fonts.ready
        ]);
      }}
    }} catch (_) {{}}
    hideHudIfEnabled();
    nativeRAF(() => nativeRAF(() => {{
      if (window.__HTML2REEL_BRIDGE__ && typeof window.__HTML2REEL_BRIDGE__.onReady === 'function') {{
        window.__HTML2REEL_BRIDGE__.onReady(seq);
      }}
    }}));
  }}

  function seekAndNotify(seconds, seq) {{
    seekTo(seconds);
    nativeRAF(() => nativeRAF(() => {{
      if (window.__HTML2REEL_BRIDGE__ && typeof window.__HTML2REEL_BRIDGE__.onFrameSettled === 'function') {{
        window.__HTML2REEL_BRIDGE__.onFrameSettled(seq);
      }}
    }}));
  }}

  window.__HTML2REEL__ = {{
    seekTo,
    seekAndNotify,
    waitUntilReady,
    nativeRAF,
    nativeCAF,
    nativePerfNow,
    nativeSetTimeout,
    nativeClearTimeout,
    nativeSetInterval,
    nativeClearInterval
  }};
}})();
</script>"#
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_metadata_tags_and_explicit_hook() {
        let html = r#"
        <html>
          <head>
            <title>My Reel Story</title>
            <meta name="video-duration" content="45.5" />
            <meta name="video-resolution" content="1080x1920" />
            <meta name="video-fps" content="60" />
          </head>
          <body><script>window.seekTo = (t) => {};</script></body>
        </html>"#;

        let meta = extract_metadata(html);
        assert_eq!(meta.title.as_deref(), Some("My Reel Story"));
        assert_eq!(meta.duration_sec, Some(45.5));
        assert_eq!(meta.width, Some(1080));
        assert_eq!(meta.height, Some(1920));
        assert_eq!(meta.fps, Some(60));
        assert!(meta.has_explicit_seek_hook);
    }

    #[test]
    fn injects_shim_before_first_script_with_fonts_and_hud_css() {
        let raw = "<html><head><style>body{}</style><script>console.log(1)</script></head></html>";
        let out = preprocess_html(
            raw,
            &PreprocessOptions {
                export_mode: true,
                auto_hide_hud: true,
                inject_offline_fonts: true,
                font_base_url: "https://appassets.androidplatform.net/assets/fonts/".into(),
            },
        );
        let shim_pos = out.html.find("data-html2reel-shim").unwrap();
        let font_pos = out.html.find("data-html2reel-fonts").unwrap();
        let hud_pos = out.html.find("data-html2reel-hud").unwrap();
        let user_script_pos = out.html.find("<script>console.log(1)</script>").unwrap();
        assert!(font_pos < user_script_pos);
        assert!(hud_pos < user_script_pos);
        assert!(shim_pos < user_script_pos);
        assert!(out.html.contains("NotoSansDevanagari.ttf"));
        assert!(out.html.contains("const EXPORT_MODE = true;"));
    }

    #[test]
    fn handles_unicode_times_resolution_and_missing_script_tag() {
        let raw = "<html><head><meta name='video-resolution' content='1920×1080'></head><body>Hi</body></html>";
        let out = preprocess_html(
            raw,
            &PreprocessOptions {
                export_mode: false,
                auto_hide_hud: false,
                inject_offline_fonts: false,
                font_base_url: String::new(),
            },
        );
        assert_eq!(out.metadata.width, Some(1920));
        assert_eq!(out.metadata.height, Some(1080));
        assert!(!out.html.contains("data-html2reel-hud"));
        assert!(out.html.contains("window.__HTML2REEL__"));
    }
}
