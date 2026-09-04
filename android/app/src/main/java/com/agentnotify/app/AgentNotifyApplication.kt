package com.agentnotify.app

import android.app.Application
import com.agentnotify.app.data.MessageRepository
import com.agentnotify.app.messaging.Notifications

class AgentNotifyApplication : Application() {
    val repository by lazy { MessageRepository(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        repository.refresh()
    }
}

