package com.html2reel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.html2reel.MainActivity
import com.html2reel.R

object RenderNotificationManager {

    const val CHANNEL_ID = "html2reel_render_channel"
    const val NOTIFICATION_ID = 4101

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.render_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.render_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        currentFrame: Int,
        totalFrames: Int,
        etaMs: Long,
        phaseLabel: String
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, RenderForegroundService::class.java).apply {
                action = RenderForegroundService.ACTION_CANCEL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = if (totalFrames > 0) ((currentFrame * 100) / totalFrames).coerceIn(0, 100) else 0
        val etaText = if (etaMs > 0) " · ETA ${formatDuration(etaMs)}" else ""

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Exporting $title")
            .setContentText("$phaseLabel ($currentFrame / $totalFrames)$etaText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, totalFrames <= 0)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
    }

    fun notifyProgress(
        context: Context,
        title: String,
        currentFrame: Int,
        totalFrames: Int,
        etaMs: Long,
        phaseLabel: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildProgressNotification(context, title, currentFrame, totalFrames, etaMs, phaseLabel)
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }
}
