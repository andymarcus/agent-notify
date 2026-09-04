package com.agentnotify.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MessageRepository(context: Context) {
    private val database = MessageDatabase(context.applicationContext)
    private val mutableMessages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = mutableMessages.asStateFlow()

    @Synchronized
    fun add(topic: String, body: String, sentAt: Long): AgentMessage {
        val receivedAt = System.currentTimeMillis()
        val normalizedTopic = topic.trim().ifEmpty { "General" }
        val id = database.insert(normalizedTopic, body, sentAt, receivedAt)
        val message = AgentMessage(id, normalizedTopic, body, sentAt, receivedAt, isRead = false)
        mutableMessages.value = listOf(message) + mutableMessages.value
        return message
    }

    @Synchronized
    fun markRead(id: Long) = setRead(id, true)

    @Synchronized
    fun markUnread(id: Long) = setRead(id, false)

    @Synchronized
    fun delete(id: Long) {
        if (database.delete(id)) {
            mutableMessages.value = mutableMessages.value.filterNot { it.id == id }
        }
    }

    private fun setRead(id: Long, isRead: Boolean) {
        val current = mutableMessages.value.firstOrNull { it.id == id } ?: return
        if (current.isRead == isRead) return
        if (database.setRead(id, isRead)) {
            mutableMessages.value = mutableMessages.value.map {
                if (it.id == id) it.copy(isRead = isRead) else it
            }
        }
    }

    @Synchronized
    fun refresh() {
        mutableMessages.value = database.all()
    }
}
