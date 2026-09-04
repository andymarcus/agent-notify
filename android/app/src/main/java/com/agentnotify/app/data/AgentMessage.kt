package com.agentnotify.app.data

data class AgentMessage(
    val id: Long,
    val topic: String,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long,
    val isRead: Boolean,
)
