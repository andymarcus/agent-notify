package com.agentnotify.app.messaging

import com.agentnotify.app.AgentNotifyApplication
import com.agentnotify.app.data.AttachmentPayload
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AgentMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val body = remoteMessage.data["body"] ?: return
        val topic = remoteMessage.data["topic"].orEmpty()
        val sentAt = remoteMessage.data["sent_at"]?.toLongOrNull()
            ?: remoteMessage.sentTime.takeIf { it > 0 }
            ?: System.currentTimeMillis()

        val attachment = remoteMessage.data["attachment_id"]?.let { id ->
            runCatching {
                AttachmentPayload(
                    id = id,
                    name = requireNotNull(remoteMessage.data["attachment_name"]),
                    mime = remoteMessage.data["attachment_mime"] ?: "application/octet-stream",
                    size = requireNotNull(remoteMessage.data["attachment_size"]?.toLongOrNull()),
                    url = requireNotNull(remoteMessage.data["attachment_url"]),
                    key = requireNotNull(remoteMessage.data["attachment_key"]),
                    iv = requireNotNull(remoteMessage.data["attachment_iv"]),
                    sha256 = requireNotNull(remoteMessage.data["attachment_sha256"]),
                )
            }.getOrNull()
        }
        val repository = (application as AgentNotifyApplication).repository
        val message = repository.add(topic, body, sentAt, attachment)
        Notifications.show(this, message)
    }

    override fun onNewToken(token: String) {
        getSharedPreferences("agent-notify", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }
}
