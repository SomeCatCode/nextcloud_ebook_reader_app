package com.somecatcode.ebookreader.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.somecatcode.ebookreader.R

/** Low-importance notifications for long running background work (foreground service type dataSync). */
object DataNotifications {
    const val CHANNEL_ID = "data_transfers"
    const val DOWNLOAD_NOTIFICATION_BASE = 4100
    const val SYNC_NOTIFICATION_ID = 4099

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.data_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.data_channel_description) }
            manager.createNotificationChannel(channel)
        }
    }

    private fun base(context: Context, title: String): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    fun downloadInfo(context: Context, id: Int, title: String, bytes: Long, total: Long): ForegroundInfo {
        val builder = base(context, title).setContentText(context.getString(R.string.data_notification_downloading))
        if (total > 0) {
            builder.setProgress(1000, ((bytes.coerceAtMost(total) * 1000) / total).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return foreground(id, builder.build())
    }

    fun syncInfo(context: Context): ForegroundInfo {
        val notification = base(context, context.getString(R.string.data_notification_syncing))
            .setProgress(0, 0, true)
            .build()
        return foreground(SYNC_NOTIFICATION_ID, notification)
    }

    private fun foreground(id: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
}
