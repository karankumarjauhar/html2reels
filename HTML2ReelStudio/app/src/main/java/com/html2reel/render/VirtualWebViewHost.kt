package com.html2reel.render

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

enum class CaptureMode(val shortLabel: String) {
    OES_TEXTURE("GPU OES"),
    PIXEL_COPY("HW Copy"),
    SOFTWARE_BITMAP("Software")
}

/**
 * Hosts an offscreen WebView sized at exact target output pixel dimensions (e.g. 1080x1920 @ 160dpi),
 * decoupled from the physical screen. Uses VirtualDisplay + Presentation for GPU rendering with
 * automatic fallback to PixelCopy or LAYER_TYPE_SOFTWARE WebView.draw() if OES frames stall or black out.
 */
class VirtualWebViewHost(
    private val appContext: Context,
    private val width: Int,
    private val height: Int,
    private val preprocessedHtml: String,
    private val oesSurface: Surface?,
    private val autoFitFixedViewport: Boolean = false
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: OffscreenPresentation? = null
    private var webView: WebView? = null
    private var usingPresentationMode = false

    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null
    private val pixelCopyBitmaps = arrayOfNulls<Bitmap>(2)

    private val fallbackBitmaps = arrayOfNulls<Bitmap>(2)
    private val fallbackCanvases = arrayOfNulls<Canvas>(2)
    private val fallbackInitialized = booleanArrayOf(false, false)

    var softwareRafAlive: Boolean = true
        private set

    @Volatile
    private var pageLoaded = CompletableDeferred<Unit>()

    @Volatile
    private var fontsReady = CompletableDeferred<Unit>()
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
        lateinit var rootContainer: FrameLayout
            private set
        lateinit var hostedWebView: WebView
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.apply {
                setLayout(width, height)
                addFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }
            setCancelable(false)

            val root = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(width, height)
                setBackgroundColor(Color.BLACK)
            }
            rootContainer = root
            hostedWebView = setupWebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(width, height)
            }
            root.addView(hostedWebView)
            setContentView(root)
        }

        fun replaceWebView(newWebView: WebView) {
            rootContainer.removeAllViews()
            newWebView.layoutParams = FrameLayout.LayoutParams(width, height)
            hostedWebView = newWebView
            rootContainer.addView(newWebView)
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        if (oesSurface != null) {
            try {
                val vd = createVirtualDisplayWithBestFlags(displayManager, oesSurface)
                virtualDisplay = vd
                val displayContext = appContext.createDisplayContext(vd.display)
                val pres = OffscreenPresentation(displayContext, vd.display, width, height) { ctx ->
                    createConfiguredWebView(ctx, softwareLayer = false)
                }
                pres.show()
                presentation = pres
                webView = pres.hostedWebView
                usingPresentationMode = true
                Log.i(TAG, "Initialized Presentation VirtualDisplay (${width}x$height, displayId=${vd.display.displayId})")
            } catch (t: Throwable) {
                Log.w(TAG, "Presentation mode unavailable, falling back to detached software WebView: ${t.message}")
                runCatching { presentation?.dismiss() }
                runCatching { virtualDisplay?.release() }
                presentation = null
                virtualDisplay = null
                usingPresentationMode = false
            }
        }

        if (!usingPresentationMode) {
            val wv = createConfiguredWebView(appContext, softwareLayer = true)
            layoutWebViewExact(wv)
            webView = wv
            ensureFallbackBuffer(0)
        }

        val activeWv = webView ?: error("WebView creation failed")
        loadAndWaitForReady(activeWv)
        if (!usingPresentationMode) {
            probeSoftwareRafResponsiveness()
        }
        usingPresentationMode
    }

    private fun createVirtualDisplayWithBestFlags(
        displayManager: DisplayManager,
        surface: Surface
    ): VirtualDisplay {
        return try {
            displayManager.createVirtualDisplay(
                "HTML2ReelOffscreen",
                width,
                height,
                DisplayMetrics.DENSITY_DEFAULT,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        } catch (_: Throwable) {
            displayManager.createVirtualDisplay(
                "HTML2ReelOffscreen",
                width,
                height,
                DisplayMetrics.DENSITY_DEFAULT,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        }
    }

    suspend fun switchToSoftwareFallback() = withContext(Dispatchers.Main) {
        Log.w(TAG, "Switching export capture to LAYER_TYPE_SOFTWARE WebView.draw()")
        destroyWebViewOnly()
        pageLoaded = CompletableDeferred()
        fontsReady = CompletableDeferred()
        fallbackInitialized.fill(false)

        val pres = presentation
        val wv = createConfiguredWebView(pres?.context ?: appContext, softwareLayer = true)
        if (pres != null && pres.isShowing) {
            pres.replaceWebView(wv)
        }
        layoutWebViewExact(wv)
        webView = wv
        ensureFallbackBuffer(0)
        loadAndWaitForReady(wv)
        probeSoftwareRafResponsiveness()
    }

    suspend fun reloadCleanFromStart(softwareLayer: Boolean) = withContext(Dispatchers.Main) {
        destroyWebViewOnly()
        pageLoaded = CompletableDeferred()
        fontsReady = CompletableDeferred()
        fallbackInitialized.fill(false)

        val pres = presentation
        val wv = createConfiguredWebView(pres?.context ?: appContext, softwareLayer = softwareLayer)
        if (pres != null && pres.isShowing) {
            pres.replaceWebView(wv)
        }
        layoutWebViewExact(wv)
        webView = wv
        if (softwareLayer) {
            ensureFallbackBuffer(0)
        }
        loadAndWaitForReady(wv)
    }

    private suspend fun probeSoftwareRafResponsiveness() {
        val start = System.currentTimeMillis()
        seekAndWaitForSettle(0.0, -102, maxStepMs = 34.0, barrierMs = 35, rafCount = 1)
        val elapsed = System.currentTimeMillis() - start
        softwareRafAlive = elapsed < 32L
        Log.i(TAG, "Software rAF probe completed in ${elapsed}ms (alive=$softwareRafAlive)")
    }

    private suspend fun loadAndWaitForReady(wv: WebView) {
        wv.loadUrl(PROJECT_INDEX_URL)
        withTimeout(15_000L) {
            pageLoaded.await()
        }
        if (autoFitFixedViewport) {
            wv.evaluateJavascript(FIT_FIXED_STAGE_JS, null)
        }
        wv.evaluateJavascript("window.__HTML2REEL__ && window.__HTML2REEL__.waitUntilReady(0);", null)
        runCatching {
            withTimeout(10_000L) {
                fontsReady.await()
            }
        }
        wv.invalidate()
    }

    suspend fun seekAndWaitForSettle(
        seconds: Double,
        seq: Int,
        maxStepMs: Double = 1000.0 / 60.0,
        barrierMs: Int = 45,
        rafCount: Int = 2
    ) = withContext(Dispatchers.Main) {
        val wv = webView ?: error("WebView not initialized")
        val waiter = CompletableDeferred<Unit>()
        frameWaiters[seq] = waiter
        wv.evaluateJavascript(
            "window.__HTML2REEL__ && window.__HTML2REEL__.seekAndNotify($seconds, $seq, $maxStepMs, $barrierMs, $rafCount);",
            null
        )
        try {
            withTimeout(5_000L) {
                waiter.await()
            }
        } finally {
            frameWaiters.remove(seq)
        }
    }

    suspend fun invalidateWebView() = withContext(Dispatchers.Main) {
        webView?.let { wv ->
            wv.invalidate()
            wv.postInvalidateOnAnimation()
        }
    }

    suspend fun capturePixelCopyBitmap(bufferIndex: Int = 0): Bitmap? {
        val framePosted = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            webView?.let { wv ->
                wv.invalidate()
                wv.postInvalidateOnAnimation()
            }
            Choreographer.getInstance().postFrameCallback { framePosted.complete(Unit) }
        }
        withTimeoutOrNull(20L) { framePosted.await() }

        val slot = bufferIndex and 1
        val resultDeferred = CompletableDeferred<Int>()
        val bmp = withContext<Bitmap?>(Dispatchers.Main) {
            val win = presentation?.window ?: return@withContext null
            val decor = win.decorView
            if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) {
                return@withContext null
            }
            val existing = pixelCopyBitmaps[slot]
            val target = if (existing != null && !existing.isRecycled) {
                existing
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    pixelCopyBitmaps[slot] = it
                }
            }
            val handler = pixelCopyHandler ?: HandlerThread("HTML2Reel-PixelCopy").let {
                it.start()
                pixelCopyThread = it
                Handler(it.looper).also { h -> pixelCopyHandler = h }
            }
            val listener = PixelCopy.OnPixelCopyFinishedListener { code ->
                resultDeferred.complete(code)
            }
            val started = runCatching {
                PixelCopy.request(win, target, listener, handler)
            }
            if (started.isFailure) {
                Log.w(TAG, "PixelCopy.request threw: ${started.exceptionOrNull()?.message}")
                return@withContext null
            }
            target
        } ?: return null

        val status = withTimeoutOrNull(1_000L) { resultDeferred.await() }
        return if (status == PixelCopy.SUCCESS) bmp else null
    }

    suspend fun drawFallbackBitmap(bufferIndex: Int = 0): Bitmap = withContext(Dispatchers.Main) {
        val wv = webView ?: error("WebView not initialized")
        val slot = bufferIndex and 1
        ensureFallbackBuffer(slot)
        val bmp = fallbackBitmaps[slot] ?: error("Fallback bitmap not allocated")
        val canvas = fallbackCanvases[slot] ?: error("Fallback canvas not allocated")
        if (wv.width != width || wv.height != height) {
            layoutWebViewExact(wv)
        }
        if (!fallbackInitialized[slot]) {
            bmp.eraseColor(Color.BLACK)
            fallbackInitialized[slot] = true
        }
        wv.draw(canvas)
        bmp
    }

    private fun ensureFallbackBuffer(slot: Int) {
        val existing = fallbackBitmaps[slot]
        if (existing == null || existing.isRecycled) {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            fallbackBitmaps[slot] = bmp
            fallbackCanvases[slot] = Canvas(bmp)
            fallbackInitialized[slot] = false
        }
    }

    private fun layoutWebViewExact(wv: WebView) {
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, width, height)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(context: Context, softwareLayer: Boolean): WebView {
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
            if (softwareLayer) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
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
            onResume()
            resumeTimers()
        }
    }

    private fun destroyWebViewOnly() {
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
    }

    suspend fun release() = withContext(Dispatchers.Main) {
        destroyWebViewOnly()
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { pixelCopyThread?.quitSafely() }
        pixelCopyThread = null
        pixelCopyHandler = null
        pixelCopyBitmaps.indices.forEach { idx ->
            runCatching { pixelCopyBitmaps[idx]?.recycle() }
            pixelCopyBitmaps[idx] = null
        }
        fallbackBitmaps.indices.forEach { idx ->
            runCatching { fallbackBitmaps[idx]?.recycle() }
            fallbackBitmaps[idx] = null
            fallbackCanvases[idx] = null
            fallbackInitialized[idx] = false
        }
    }

    companion object {
        private const val TAG = "HTML2ReelHost"
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val PROJECT_INDEX_URL = "https://$ASSET_DOMAIN/project/index.html"
        const val FONT_BASE_URL = "https://$ASSET_DOMAIN/assets/fonts/"

        private val FIT_FIXED_STAGE_JS = """
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
    }
}
