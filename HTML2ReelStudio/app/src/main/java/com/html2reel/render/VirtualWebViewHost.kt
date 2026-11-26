package com.html2reel.render

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts an offscreen WebView sized at exact target output pixel dimensions (e.g. 1080x1920 @ 160dpi),
 * decoupled from the physical screen. Uses VirtualDisplay + Presentation for zero-copy GPU rendering
 * with automatic fallback to detached WebView.draw() if an OEM blocks Presentation from a Service.
 */
class VirtualWebViewHost(
    private val appContext: Context,
    private val width: Int,
    private val height: Int,
    private val preprocessedHtml: String,
    private val oesSurface: Surface?
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: OffscreenPresentation? = null
    private var webView: WebView? = null
    private var usingPresentationMode = false

    private var fallbackBitmap: Bitmap? = null
    private var fallbackCanvas: Canvas? = null

    private val pageLoaded = CompletableDeferred<Unit>()
    private val fontsReady = CompletableDeferred<Unit>()
    private val frameWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    private inner class JsBridge {
        @JavascriptInterface
        fun onReady(@Suppress("UNUSED_PARAMETER") seq: Int) {
            fontsReady.complete(Unit)
        }

        @JavascriptInterface
        fun onFrameSettled(seq: Int) {
            frameWaiters.remove(seq)?.complete(Unit)
        }
    }

    private class OffscreenPresentation(
        displayContext: Context,
        display: Display,
        private val width: Int,
        private val height: Int,
        private val setupWebView: (Context) -> WebView
    ) : Presentation(displayContext, display) {
        lateinit var hostedWebView: WebView
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(width, height)
                setBackgroundColor(Color.BLACK)
            }
            hostedWebView = setupWebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(width, height)
            }
            root.addView(hostedWebView)
            setContentView(root)
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        if (oesSurface != null) {
            try {
                val vd = displayManager.createVirtualDisplay(
                    "HTML2ReelOffscreen",
                    width,
                    height,
                    DisplayMetrics.DENSITY_DEFAULT,
                    oesSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
                virtualDisplay = vd
                val displayContext = appContext.createDisplayContext(vd.display)
                val pres = OffscreenPresentation(displayContext, vd.display, width, height) { ctx ->
                    createConfiguredWebView(ctx)
                }
                pres.show()
                presentation = pres
                webView = pres.hostedWebView
                usingPresentationMode = true
            } catch (_: Throwable) {
                // OEM ROM blocked Presentation from Service; clean up and fall back to detached WebView.draw().
                runCatching { presentation?.dismiss() }
                runCatching { virtualDisplay?.release() }
                presentation = null
                virtualDisplay = null
                usingPresentationMode = false
            }
        }

        if (!usingPresentationMode) {
            val wv = createConfiguredWebView(appContext)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, width, height)
            webView = wv
            fallbackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            fallbackCanvas = Canvas(fallbackBitmap!!)
        }

        webView?.loadUrl(PROJECT_INDEX_URL)
        withTimeout(15_000L) {
            pageLoaded.await()
        }
        webView?.evaluateJavascript("window.__HTML2REEL__ && window.__HTML2REEL__.waitUntilReady(0);", null)
        runCatching {
            withTimeout(10_000L) {
                fontsReady.await()
            }
        }
        usingPresentationMode
    }

    suspend fun seekAndWaitForSettle(seconds: Double, seq: Int) = withContext(Dispatchers.Main) {
        val wv = webView ?: error("WebView not initialized")
        val waiter = CompletableDeferred<Unit>()
        frameWaiters[seq] = waiter
        wv.evaluateJavascript("window.__HTML2REEL__ && window.__HTML2REEL__.seekAndNotify($seconds, $seq);", null)
        try {
            withTimeout(5_000L) {
                waiter.await()
            }
        } finally {
            frameWaiters.remove(seq)
        }
    }

    suspend fun drawFallbackBitmap(): Bitmap = withContext(Dispatchers.Main) {
        val wv = webView ?: error("WebView not initialized")
        val bmp = fallbackBitmap ?: error("Fallback bitmap not allocated")
        val canvas = fallbackCanvas ?: error("Fallback canvas not allocated")
        bmp.eraseColor(Color.BLACK)
        wv.draw(canvas)
        bmp
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(context: Context): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
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

        return WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            setInitialScale(100)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                textZoom = 100
            }

            addJavascriptInterface(JsBridge(), "__HTML2REEL_BRIDGE__")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    pageLoaded.complete(Unit)
                }
            }
        }
    }

    suspend fun release() = withContext(Dispatchers.Main) {
        frameWaiters.values.forEach { it.cancel() }
        frameWaiters.clear()
        runCatching {
            webView?.apply {
                stopLoading()
                removeJavascriptInterface("__HTML2REEL_BRIDGE__")
                loadUrl("about:blank")
                destroy()
            }
        }
        webView = null
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { fallbackBitmap?.recycle() }
        fallbackBitmap = null
        fallbackCanvas = null
    }

    companion object {
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val PROJECT_INDEX_URL = "https://$ASSET_DOMAIN/project/index.html"
        const val FONT_BASE_URL = "https://$ASSET_DOMAIN/assets/fonts/"
    }
}
