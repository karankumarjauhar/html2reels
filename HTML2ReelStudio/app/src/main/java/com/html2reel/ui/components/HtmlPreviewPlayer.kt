package com.html2reel.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.html2reel.model.AspectPreset
import com.html2reel.render.VirtualWebViewHost
import com.html2reel.ui.theme.StudioBorder
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

/**
 * Aspect-ratio-locked live preview player using WebViewAssetLoader and the same
 * window.__HTML2REEL__.seekTo(t) deterministic clock used during offline exports.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreviewPlayer(
    preprocessedHtml: String,
    preset: AspectPreset,
    currentTimeSec: Float,
    isPlaying: Boolean,
    durationSec: Float,
    onTimeAdvance: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    // Advance playhead smoothly at ~30 Hz when Play is active.
    LaunchedEffect(isPlaying, durationSec) {
        if (!isPlaying) return@LaunchedEffect
        var lastTick = System.nanoTime()
        var cursor = currentTimeSec
        while (true) {
            delay(33L)
            val now = System.nanoTime()
            val dt = (now - lastTick) / 1_000_000_000f
            lastTick = now
            cursor += dt
            if (cursor >= durationSec) {
                cursor = 0f
            }
            onTimeAdvance(cursor)
        }
    }

    // Drive deterministic seek whenever time changes after page load.
    LaunchedEffect(currentTimeSec, pageReady) {
        if (pageReady) {
            webViewRef?.evaluateJavascript(
                "window.__HTML2REEL__ && window.__HTML2REEL__.seekTo(${currentTimeSec.toDouble()});",
                null
            )
        }
    }

    // Re-fit fixed-pixel documents (e.g. 1080x1920 canvases) whenever aspect preset changes.
    LaunchedEffect(preset, pageReady) {
        if (pageReady) {
            delay(40L)
            webViewRef?.evaluateJavascript(FIT_PREVIEW_JS, null)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        contentAlignment = Alignment.Center
    ) {
        val boxShape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .aspectRatio(preset.aspectRatio, matchHeightConstraintsFirst = preset.height >= preset.width)
                .clip(boxShape)
                .background(androidx.compose.ui.graphics.Color.Black)
                .border(1.dp, StudioBorder, boxShape),
            contentAlignment = Alignment.Center
        ) {
            key(preprocessedHtml) {
                var ownedWebView by remember { mutableStateOf<WebView?>(null) }
                DisposableEffect(Unit) {
                    onDispose {
                        ownedWebView?.apply {
                            stopLoading()
                            loadUrl("about:blank")
                            destroy()
                        }
                        if (webViewRef === ownedWebView) {
                            webViewRef = null
                        }
                        ownedWebView = null
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        pageReady = false
                        val assetLoader = WebViewAssetLoader.Builder()
                            .setDomain(VirtualWebViewHost.ASSET_DOMAIN)
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                            .addPathHandler("/project/") { path ->
                                if (path == "index.html") {
                                    WebResourceResponse(
                                        "text/html",
                                        "utf-8",
                                        ByteArrayInputStream(preprocessedHtml.toByteArray(Charsets.UTF_8))
                                    )
                                } else {
                                    null
                                }
                            }
                            .build()

                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(Color.BLACK)
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            overScrollMode = View.OVER_SCROLL_NEVER
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                allowFileAccess = false
                                allowContentAccess = false
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                textZoom = 100
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    view.evaluateJavascript(FIT_PREVIEW_JS, null)
                                    pageReady = true
                                }
                            }
                            ownedWebView = this
                            webViewRef = this
                            loadUrl(VirtualWebViewHost.PROJECT_INDEX_URL)
                        }
                    },
                    update = { view ->
                        if (pageReady) {
                            view.evaluateJavascript(FIT_PREVIEW_JS, null)
                        }
                    }
                )
            }
        }
    }
}

private val FIT_PREVIEW_JS = """
    (function() {
      var doc = document.documentElement;
      var body = document.body;
      if (!doc) return;
      doc.style.transform = '';
      doc.style.width = '';
      doc.style.height = '';
      var vw = window.innerWidth || doc.clientWidth || 1;
      var vh = window.innerHeight || doc.clientHeight || 1;
      var w = Math.max(doc.scrollWidth || 0, body ? (body.scrollWidth || 0) : 0);
      var h = Math.max(doc.scrollHeight || 0, body ? (body.scrollHeight || 0) : 0);
      if (w > vw + 2 || h > vh + 2) {
        var s = Math.min(vw / w, vh / h);
        doc.style.transformOrigin = '0 0';
        doc.style.transform = 'scale(' + s + ')';
        doc.style.width = w + 'px';
        doc.style.height = h + 'px';
        doc.style.overflow = 'hidden';
        if (body) body.style.overflow = 'hidden';
      }
    })();
""".trimIndent()
