package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatMessage

class ChatNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "hello_sir_messages"
        const val CALL_CHANNEL_ID = "hello_sir_calls"
        const val UPLOAD_CHANNEL_ID = "hello_sir_uploads"
        const val EXTRA_CHAT_TARGET = "extra_chat_target"
        const val EXTRA_OPEN_CALL = "extra_open_call"
    }

    init {
        createChannels()
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val msgChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                enableVibration(true)
            }
            manager.createNotificationChannel(msgChannel)

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                context.getString(R.string.call_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.call_notification_channel_desc)
                enableVibration(true)
                setShowBadge(false)
            }
            manager.createNotificationChannel(callChannel)

            val uploadChannel = NotificationChannel(
                UPLOAD_CHANNEL_ID,
                "Media Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while uploading photos or Reels"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(uploadChannel)
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showUploadProgress(uploadId: Int, title: String, percent: Int) {
        if (!hasNotificationPermission()) return
        val clamped = percent.coerceIn(0, 100)
        val notification = NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(title)
            .setContentText("$clamped%")
            .setProgress(100, clamped, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(uploadId, notification)
        }
    }

    fun completeUploadNotification(uploadId: Int, title: String, success: Boolean) {
        if (!hasNotificationPermission()) return
        if (!success) {
            runCatching { NotificationManagerCompat.from(context).cancel(uploadId) }
            return
        }
        val notification = NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(title)
            .setContentText("Uploaded (100%)")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(uploadId, notification)
        }
    }

    fun showIncomingMessageNotification(message: ChatMessage, senderDisplayName: String) {
        if (!hasNotificationPermission()) return

        val targetId = if (message.isBroadcast) BROADCAST_TARGET_ID else message.sender
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_TARGET, targetId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            targetId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedActor = "$senderDisplayName (${message.sender})"
        val title = if (message.isBroadcast) {
            "Global Broadcast • $formattedActor"
        } else {
            formattedActor
        }

        val preview = message.previewSummary()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(
                message.dedupeKey.hashCode(),
                notification
            )
        }
    }
}
