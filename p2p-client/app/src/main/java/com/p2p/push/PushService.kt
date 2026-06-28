package com.p2p.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.p2p.data.local.SettingsRepository
import com.p2p.data.repository.AuthRepository
import com.p2p.data.repository.MessagingService
import com.p2p.ui.MainActivity
import com.p2p_client.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Принимает FCM data-push «есть новое сообщение», сам подтягивает pending,
 * расшифровывает (E2EE) и показывает локальные уведомления.
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {

    @Inject lateinit var messagingService: MessagingService
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { authRepository.registerFcmToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        scope.launch {
            // Сообщения подтягиваем всегда (синхронизация), показ уведомлений — по настройке.
            val messages = messagingService.fetchPendingMessages().getOrNull() ?: return@launch
            if (!settingsRepository.notificationsEnabled.first()) return@launch
            messages.forEach { msg ->
                showMessageNotification(msg.id.hashCode(), msg.content)
            }
        }
    }

    private fun showMessageNotification(id: Int, text: String) {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.p2p_icon)
            .setContentTitle("New message")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "messages"
    }
}
