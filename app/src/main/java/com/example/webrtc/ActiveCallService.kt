package com.example.webrtc

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ChatRepository
import com.example.notifications.ChatNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service (stopWithTask="false") keeping WebRTC audio & video calls alive
 * when the app is minimized or swiped from Recents, backed by a CallStyle notification
 * with Mute/Unmute, Hang Up, and tap-to-reopen CallScreen controls.
 */
class ActiveCallService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChatNotificationHelper(applicationContext).createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = ChatRepository.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> {
                repo.webRTCClient.toggleMute()
            }
            ACTION_HANG_UP -> {
                repo.webRTCClient.declineOrEndCall()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_ANSWER_CALL -> {
                val withVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                repo.webRTCClient.acceptIncomingCall(withVideo)
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(ChatNotificationHelper.EXTRA_OPEN_CALL, true)
                }
                runCatching { startActivity(openIntent) }
            }
        }

        val initialState = repo.webRTCClient.callState.value
        if (!initialState.isActive) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        promoteToForeground(initialState)
        startObservingCallState(repo.webRTCClient)
        return START_STICKY
    }

    private fun startObservingCallState(webRTCClient: WebRTCClient) {
        if (observerJob?.isActive == true) return
        observerJob = serviceScope.launch {
            webRTCClient.callState.collect { state ->
                if (!state.isActive) {
                    stopSelfSafely()
                } else {
                    promoteToForeground(state)
                }
            }
        }
    }

    private fun promoteToForeground(state: CallUiState) {
        val notification = buildCallNotification(state)
        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var type = 0
            if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (state.isVideoCall && hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            type
        } else {
            0
        }

        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        }
    }

    private fun buildCallNotification(state: CallUiState): Notification {
        val reopenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatNotificationHelper.EXTRA_OPEN_CALL, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            101,
            reopenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(this, ActiveCallService::class.java).apply {
            action = ACTION_HANG_UP
        }
        val hangupPendingIntent = PendingIntent.getService(
            this,
            102,
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, ActiveCallService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            103,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerName = state.remoteUserName.ifBlank { state.remoteUserId.ifBlank { "Hello Sir Call" } }
        val callerPerson = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val builder = NotificationCompat.Builder(this, ChatNotificationHelper.CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setColorized(true)
            .addPerson(callerPerson)

        if (state.status == CallStatus.INCOMING_RINGING) {
            val answerIntent = Intent(this, ActiveCallService::class.java).apply {
                action = ACTION_ANSWER_CALL
                putExtra(EXTRA_IS_VIDEO, state.isVideoCall)
            }
            val answerPendingIntent = PendingIntent.getService(
                this,
                104,
                answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callerPerson,
                    hangupPendingIntent,
                    answerPendingIntent
                )
            )
            builder.setContentText(
                if (state.isVideoCall) "Incoming WebRTC video call" else "Incoming WebRTC audio call"
            )
        } else {
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    callerPerson,
                    hangupPendingIntent
                )
            )
            val statusSub = when (state.status) {
                CallStatus.OUTGOING_RINGING -> "Calling…"
                CallStatus.CONNECTING -> state.iceStatusLabel
                CallStatus.CONNECTED -> {
                    val mins = state.durationSeconds / 60L
                    val secs = state.durationSeconds % 60L
                    val mode = if (state.isVideoCall) "Video" else "Audio"
                    String.format("%s • %02d:%02d", mode, mins, secs)
                }
                else -> "Active call"
            }
            builder.setContentText(statusSub)
            builder.addAction(
                R.drawable.ic_notification_chat,
                if (state.isMuted) "Unmute" else "Mute",
                mutePendingIntent
            )
        }

        return builder.build()
    }

    private fun stopSelfSafely() {
        observerJob?.cancel()
        observerJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 7001
        const val ACTION_START_OR_UPDATE = "com.example.webrtc.ACTION_START_OR_UPDATE"
        const val ACTION_TOGGLE_MUTE = "com.example.webrtc.ACTION_TOGGLE_MUTE"
        const val ACTION_HANG_UP = "com.example.webrtc.ACTION_HANG_UP"
        const val ACTION_ANSWER_CALL = "com.example.webrtc.ACTION_ANSWER_CALL"
        const val EXTRA_IS_VIDEO = "extra_is_video"

        fun syncService(context: Context, isActive: Boolean) {
            if (!isActive) {
                runCatching {
                    context.stopService(Intent(context, ActiveCallService::class.java))
                }
                return
            }
            val intent = Intent(context, ActiveCallService::class.java).apply {
                action = ACTION_START_OR_UPDATE
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
