package com.agentnotify.app.messaging

import com.agentnotify.app.AgentNotifyApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AgentMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val body = remoteMessage.data["body"] ?: return
        val topic = remoteMessage.data["topic"].orEmpty()
        val sentAt = remoteMessage.data["sent_at"]?.toLongOrNull()
            ?: remoteMessage.sentTime.takeIf { it > 0 }
            ?: System.currentTimeMillis()

        val repository = (application as AgentNotifyApplication).repository
        val message = repository.add(topic, body, sentAt)
        Notifications.show(this, message)
    }

    override fun onNewToken(token: String) {
        getSharedPreferences("agent-notify", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }
}

