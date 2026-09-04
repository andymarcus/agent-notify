package com.agentnotify.app.data

data class AgentMessage(
    val id: Long,
    val topic: String,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long,
    val isRead: Boolean,
    val attachmentId: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val attachmentUrl: String? = null,
    val attachmentKey: String? = null,
    val attachmentIv: String? = null,
    val attachmentSha256: String? = null,
    val attachmentStatus: String? = null,
    val attachmentPath: String? = null,
    val attachmentError: String? = null,
)
