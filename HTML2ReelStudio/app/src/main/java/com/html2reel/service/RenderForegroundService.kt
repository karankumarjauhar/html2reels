package com.html2reel.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.html2reel.Html2ReelApp
import com.html2reel.model.RenderConfig
import com.html2reel.model.RenderState
import com.html2reel.render.RenderPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RenderForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderJob: Job? = null
    private var activePipeline: RenderPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelActiveRender()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val app = application as Html2ReelApp
                val config = app.container.pendingRenderConfig
                if (config == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithType(config)
                launchRender(app, config)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 (API 35+) 6-hour mediaProcessing budget exhausted: cancel pipeline & stop gracefully.
        cancelActiveRender()
        stopSelf(startId)
    }

    private fun startForegroundWithType(config: RenderConfig) {
        RenderNotificationManager.ensureChannel(this)
        val notification = RenderNotificationManager.buildProgressNotification(
            context = this,
            title = config.projectTitle,
            currentFrame = 0,
            totalFrames = config.totalFrames,
            etaMs = 0L,
            phaseLabel = "Starting render..."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                RenderNotificationManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                RenderNotificationManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(RenderNotificationManager.NOTIFICATION_ID, notification)
        }
    }

    private fun launchRender(app: Html2ReelApp, config: RenderConfig) {
        if (renderJob?.isActive == true) return
        acquireWakeLock()
        val pipeline = RenderPipeline(this, app.container.rustCore)
        activePipeline = pipeline

        renderJob = serviceScope.launch {
            try {
                val finalState = pipeline.execute(config) { progress ->
                    app.container.updateRenderState(progress)
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyMs >= 450L || progress.currentFrame == progress.totalFrames) {
                        lastNotifyMs = now
                        RenderNotificationManager.notifyProgress(
                            context = this@RenderForegroundService,
                            title = config.projectTitle,
                            currentFrame = progress.currentFrame,
                            totalFrames = progress.totalFrames,
                            etaMs = progress.etaMs,
                            phaseLabel = progress.phaseLabel
                        )
                    }
                }
                app.container.updateRenderState(finalState)
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelActiveRender() {
        activePipeline?.cancel()
        renderJob?.cancel()
        (application as? Html2ReelApp)?.container?.updateRenderState(RenderState.Cancelled)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HTML2Reel:RenderWakeLock").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        activePipeline?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.html2reel.action.START_RENDER"
        const val ACTION_CANCEL = "com.html2reel.action.CANCEL_RENDER"

        fun start(context: Context) {
            val intent = Intent(context, RenderForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, RenderForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
