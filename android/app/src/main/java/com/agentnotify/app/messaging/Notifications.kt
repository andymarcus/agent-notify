package com.agentnotify.app.messaging

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agentnotify.app.MainActivity
import com.agentnotify.app.R
import com.agentnotify.app.data.AgentMessage

object Notifications {
    private const val CHANNEL_ID = "agent_messages"
    private const val DOWNLOAD_CHANNEL_ID = "attachment_downloads"
    private const val DOWNLOAD_NOTIFICATION_OFFSET = 1_000_000_000
    private const val DOWNLOAD_RESULT_TAG = "download_result"

    fun createChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "File downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of attachments being saved to Downloads"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Messages sent by your AI agents"
            }
        )
    }

    fun show(context: Context, message: AgentMessage) {
        if (!canNotify(context)) return

        val pendingIntent = openMessageIntent(context, message.id)
        val textPreview = message.body
            .replace(Regex("[#*_>`~-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val preview = message.attachmentName?.let { "$textPreview  •  File: $it" } ?: textPreview

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.topic)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(message.id.toInt(), notification)
    }

    fun downloadNotificationId(messageId: Long): Int = DOWNLOAD_NOTIFICATION_OFFSET + messageId.toInt()

    /** Ongoing notification backing the download's foreground service. */
    fun downloadProgress(context: Context, messageId: Long, name: String?, bytesWritten: Long, totalBytes: Long?): Notification {
        val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name ?: "Attachment")
            .setContentIntent(openMessageIntent(context, messageId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (totalBytes != null && totalBytes > 0) {
            val percent = (bytesWritten * 100 / totalBytes).toInt().coerceIn(0, 100)
            builder.setContentText("Downloading - $percent%").setProgress(100, percent, false)
        } else {
            builder.setContentText("Downloading").setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun showDownloadResult(context: Context, message: AgentMessage) {
        if (!canNotify(context)) return
        val succeeded = message.attachmentStatus == "ready"
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(if (succeeded) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(message.attachmentName ?: "Attachment")
            .setContentText(if (succeeded) "Saved to Downloads" else message.attachmentError ?: "Download failed")
            .setContentIntent(openMessageIntent(context, message.id))
            .setAutoCancel(true)
            .build()
        // Tagged so it is not cleared when WorkManager removes the foreground progress notification.
        NotificationManagerCompat.from(context).notify(DOWNLOAD_RESULT_TAG, downloadNotificationId(message.id), notification)
    }

    private fun canNotify(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openMessageIntent(context: Context, messageId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("message_id", messageId)
        }
        return PendingIntent.getActivity(
            context,
            messageId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
