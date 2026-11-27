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
             .hud, #hud, .controls, #controls, .start-overlay, #start-overlay, .play-overlay, #play-overlay, [data-export-hide] { \
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

    if duration_sec.is_none() {
        duration_sec = extract_duration_from_js(html)
            .or_else(|| extract_duration_from_title(title.as_deref()));
    }

    let has_iife = lower.contains("(function") || lower.contains("(()=>{") || lower.contains("() => {");
    let has_explicit_seek_hook = lower.contains("window.seekto")
        || lower.contains("window.renderframe")
        || lower.contains("globalthis.seekto")
        || lower.contains("globalthis.renderframe")
        || (!has_iife && (lower.contains("function seekto") || lower.contains("function renderframe")));

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
    let trimmed = raw.trim();
    if let Some((m_str, s_str)) = trimmed.split_once(':') {
        if !m_str.contains(':') && !s_str.contains(':') {
            let mins = m_str.trim().parse::<u32>().ok()?;
            let secs = s_str.trim().parse::<f64>().ok()?;
            if secs >= 0.0 && secs < 60.0 {
                let total = (mins as f64) * 60.0 + secs;
                if total.is_finite() && total > 0.0 && total <= 3600.0 {
                    return Some(total);
                }
            }
            return None;
        }
    }
    let cleaned = trimmed.trim_end_matches('s').trim();
    let secs = cleaned.parse::<f64>().ok()?;
    if secs.is_finite() && secs > 0.0 && secs <= 3600.0 {
        Some(secs)
    } else {
        None
    }
}

/// Scans `<script>` blocks for common animation duration constants such as
/// `const DURATION = 60.0`, `totalDuration = 45`, or `DURATION_MS = 60000`.
fn extract_duration_from_js(html: &str) -> Option<f64> {
    let lower = html.to_ascii_lowercase();
    let mut best: Option<(u8, f64)> = None;
    let mut cursor = 0usize;

    while let Some(rel_open) = lower[cursor..].find("<script") {
        let open_pos = cursor + rel_open;
        let Some(rel_gt) = lower[open_pos..].find('>') else {
            break;
        };
        let header = &lower[open_pos..open_pos + rel_gt];
        let body_start = open_pos + rel_gt + 1;
        let Some(rel_close) = lower[body_start..].find("</script>") else {
            break;
        };
        let body_end = body_start + rel_close;
        cursor = body_end + "</script>".len();

        if !header.contains("data-html2reel-shim") {
            scan_script_for_duration(&lower[body_start..body_end], &mut best);
        }
    }

    best.map(|(_, secs)| secs)
}

fn scan_script_for_duration(script: &str, best: &mut Option<(u8, f64)>) {
    const SEC_KEYS: &[&str] = &[
        "total_duration_sec",
        "total_duration",
        "video_duration",
        "reel_duration",
        "duration_seconds",
        "duration_sec",
        "duration_secs",
        "totaldurationsec",
        "totalduration",
        "videoduration",
        "reelduration",
        "durationseconds",
        "durationsec",
        "durationsecs",
        "duration",
    ];
    const MS_KEYS: &[&str] = &[
        "total_duration_ms",
        "video_duration_ms",
        "duration_ms",
        "totaldurationms",
        "videodurationms",
        "durationms",
    ];

    for &(is_ms, keys) in &[(false, SEC_KEYS), (true, MS_KEYS)] {
        for &key in keys {
            let mut pos = 0usize;
            while let Some(rel) = script[pos..].find(key) {
                let idx = pos + rel;
                let end = idx + key.len();
                pos = end;

                if let Some((priority, secs)) = try_parse_js_assignment(script, idx, end, key, is_ms) {
                    if best.map_or(true, |(p, _)| priority < p) {
                        *best = Some((priority, secs));
                    }
                }
            }
        }
    }
}

fn try_parse_js_assignment(
    script: &str,
    idx: usize,
    end: usize,
    key: &str,
    is_ms: bool,
) -> Option<(u8, f64)> {
    let bytes = script.as_bytes();
    let prev_ok = idx == 0 || {
        let b = bytes[idx - 1];
        !b.is_ascii_alphanumeric() && b != b'_' && b != b'$' && b != b'.'
    };
    let next_ok = end >= bytes.len()
        || (!bytes[end].is_ascii_alphanumeric() && bytes[end] != b'_' && bytes[end] != b'$');
    if !prev_ok || !next_ok {
        return None;
    }

    let line_start = script[..idx].rfind('\n').map(|p| p + 1).unwrap_or(0);
    if script[line_start..idx].trim_start().starts_with("//") {
        return None;
    }

    let rest = script[end..].trim_start();
    if rest.starts_with("==") {
        return None;
    }
    let after_op = rest.strip_prefix('=').or_else(|| rest.strip_prefix(':'))?;

    let val_part = after_op.trim_start();
    let num_end = val_part
        .find(|c: char| !c.is_ascii_digit() && c != '.')
        .unwrap_or(val_part.len());
    if num_end == 0 {
        return None;
    }
    let raw_num = val_part[..num_end].parse::<f64>().ok()?;
    let trailing = val_part[num_end..].trim_start();
    if let Some(next_ch) = trailing.chars().next() {
        if !matches!(next_ch, ';' | ',' | '\n' | '\r' | ')' | '}' | '/') {
            return None;
        }
    }

    let secs = if is_ms { raw_num / 1000.0 } else { raw_num };
    if !secs.is_finite() || secs < 1.0 || secs > 3600.0 {
        return None;
    }

    // Prefer `const`/`let`/`var` top-level duration constants over generic object keys.
    let prefix = script[line_start..idx].trim_end();
    let is_decl = ends_with_keyword(prefix, "const")
        || ends_with_keyword(prefix, "let")
        || ends_with_keyword(prefix, "var");
    let priority = match (key == "duration", is_decl) {
        (false, true) => 0u8,
        (false, false) => 1u8,
        (true, true) => 2u8,
        (true, false) => 3u8,
    };
    Some((priority, secs))
}

fn ends_with_keyword(prefix: &str, keyword: &str) -> bool {
    let Some(stripped) = prefix.strip_suffix(keyword) else {
        return false;
    };
    stripped
        .as_bytes()
        .last()
        .map_or(true, |b| !b.is_ascii_alphanumeric() && *b != b'_' && *b != b'$')
}

/// Extracts duration hints from `<title>`, such as `"60s Vertical Story"` or `"45 sec Reel"`.
fn extract_duration_from_title(title: Option<&str>) -> Option<f64> {
    let text = title?.to_ascii_lowercase();
    let bytes = text.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() {
            let prev_ok = i == 0 || !bytes[i - 1].is_ascii_alphanumeric();
            let mut j = i;
            let mut seen_dot = false;
            while j < bytes.len() && (bytes[j].is_ascii_digit() || (!seen_dot && bytes[j] == b'.')) {
                if bytes[j] == b'.' {
                    seen_dot = true;
                }
                j += 1;
            }
            if prev_ok && j > i {
                let num_str = &text[i..j];
                let after_ws = text[j..].trim_start();
                let unit_len = if after_ws.starts_with("seconds") {
                    Some(7)
                } else if after_ws.starts_with("second") {
                    Some(6)
                } else if after_ws.starts_with("secs") {
                    Some(4)
                } else if after_ws.starts_with("sec") {
                    Some(3)
                } else if after_ws.starts_with('s') {
                    Some(1)
                } else {
                    None
                };
                if let Some(ulen) = unit_len {
                    let after_unit = &after_ws[ulen..];
                    let end_ok = after_unit
                        .chars()
                        .next()
                        .map_or(true, |c| !c.is_ascii_alphabetic());
                    if end_ok {
                        if let Ok(secs) = num_str.parse::<f64>() {
                            if secs.is_finite() && secs >= 1.0 && secs <= 3600.0 {
                                return Some(secs);
                            }
                        }
                    }
                }
            }
            i = j;
        } else {
            i += 1;
        }
    }
    None
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

  let autoStartTriggered = false;
  function tryAutoStartOverlay() {{
    if (autoStartTriggered) return;
    if (typeof window.seekTo === 'function' || typeof window.renderFrame === 'function') return;
    try {{
      const el = document.querySelector('#start-overlay, .start-overlay, #play-overlay, .play-overlay, [data-export-start]');
      if (el) {{
        autoStartTriggered = true;
        el.click();
      }}
    }} catch (_) {{}}
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

    if (EXPORT_MODE || s > 0) {{
      tryAutoStartOverlay();
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

  function settlePaintBarrier(maxFallbackMs, cb) {{
    let done = false;
    const finish = () => {{
      if (done) return;
      done = true;
      cb();
    }};
    nativeRAF(() => nativeRAF(finish));
    nativeSetTimeout(finish, maxFallbackMs);
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
    if (EXPORT_MODE) {{
      tryAutoStartOverlay();
    }}
    hideHudIfEnabled();
    settlePaintBarrier(120, () => {{
      if (window.__HTML2REEL_BRIDGE__ && typeof window.__HTML2REEL_BRIDGE__.onReady === 'function') {{
        window.__HTML2REEL_BRIDGE__.onReady(seq);
      }}
    }});
  }}

  function seekAndNotify(seconds, seq) {{
    seekTo(seconds);
    settlePaintBarrier(80, () => {{
      if (window.__HTML2REEL_BRIDGE__ && typeof window.__HTML2REEL_BRIDGE__.onFrameSettled === 'function') {{
        window.__HTML2REEL_BRIDGE__.onFrameSettled(seq);
      }}
    }});
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
    fn extracts_duration_from_js_constants_and_title_fallback() {
        let js_html = r#"
        <html>
          <head><title>EK PURANI LALTAIN · EK RAAT — 60s Vertical Story</title></head>
          <body>
            <script>
              (function() {
                const DURATION = 60.0;
                const FADE = 0.45;
                function renderFrame(t) {}
              })();
            </script>
          </body>
        </html>"#;
        let meta = extract_metadata(js_html);
        assert_eq!(meta.duration_sec, Some(60.0));
        assert!(!meta.has_explicit_seek_hook);

        let ms_html = "<html><script>const totalDurationMs = 45000;</script></html>";
        assert_eq!(extract_metadata(ms_html).duration_sec, Some(45.0));

        let title_only = "<html><head><title>Promo Reel — 35s</title></head><body></body></html>";
        assert_eq!(extract_metadata(title_only).duration_sec, Some(35.0));

        let subsecond_only = "<html><script>const duration = 0.3;</script></html>";
        assert_eq!(extract_metadata(subsecond_only).duration_sec, None);
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
        assert!(out.html.contains("tryAutoStartOverlay"));
        assert!(out.html.contains("#start-overlay"));
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
