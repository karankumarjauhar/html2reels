package com.html2reel.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
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

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef = null
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
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
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
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            allowFileAccess = false
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                pageReady = true
                            }
                        }
                        webViewRef = this
                        loadUrl(VirtualWebViewHost.PROJECT_INDEX_URL)
                    }
                },
                update = { view ->
                    // Scale page cleanly inside preview viewport width matching target design width.
                    val scalePercent = ((view.width.coerceAtLeast(1).toFloat() / preset.width.toFloat()) * 100f)
                        .toInt()
                        .coerceIn(10, 200)
                    if (view.width > 0) {
                        view.setInitialScale(scalePercent)
                    }
                }
            )
        }
    }
}
