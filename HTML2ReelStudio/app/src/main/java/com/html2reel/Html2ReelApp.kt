package com.html2reel

import android.app.Application
import android.webkit.WebView
import com.html2reel.bridge.RustCoreRepository
import com.html2reel.data.RecentProjectsStore
import com.html2reel.model.RenderConfig
import com.html2reel.model.RenderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual DI container (AppContainer pattern) keeping build fast and service wiring direct.
 */
class AppContainer(app: Application) {
    val rustCore = RustCoreRepository()
    val recentProjectsStore = RecentProjectsStore(app)

    private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
    val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

    @Volatile
    var pendingRenderConfig: RenderConfig? = null

    fun updateRenderState(state: RenderState) {
        _renderState.value = state
    }
}

class Html2ReelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Must be called before any WebView instance is created so offscreen documents draw fully.
        WebView.enableSlowWholeDocumentDraw()
        container = AppContainer(this)
    }
}
