package com.agentnotify.app.data

import android.content.Context
import com.agentnotify.app.crypto.AttachmentCrypto
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MessageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = MessageDatabase(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableMessages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = mutableMessages.asStateFlow()

    @Synchronized
    fun add(topic: String, body: String, sentAt: Long, attachment: AttachmentPayload? = null): AgentMessage {
        val receivedAt = System.currentTimeMillis()
        val normalizedTopic = topic.trim().ifEmpty { "General" }
        val id = database.insert(normalizedTopic, body, sentAt, receivedAt, attachment)
        val message = AgentMessage(
            id, normalizedTopic, body, sentAt, receivedAt, isRead = false,
            attachmentId = attachment?.id,
            attachmentName = attachment?.name,
            attachmentMime = attachment?.mime,
            attachmentSize = attachment?.size,
            attachmentUrl = attachment?.url,
            attachmentKey = attachment?.key,
            attachmentIv = attachment?.iv,
            attachmentSha256 = attachment?.sha256,
            attachmentStatus = attachment?.let { "offered" },
        )
        mutableMessages.value = listOf(message) + mutableMessages.value
        return message
    }

    @Synchronized
    fun markRead(id: Long) = setRead(id, true)

    @Synchronized
    fun markUnread(id: Long) = setRead(id, false)

    @Synchronized
    fun delete(id: Long) {
        val message = mutableMessages.value.firstOrNull { it.id == id }
        if (database.delete(id)) {
            message?.attachmentId?.let { attachmentId ->
                runCatching { File(appContext.filesDir, "attachments/$attachmentId").deleteRecursively() }
            }
            mutableMessages.value = mutableMessages.value.filterNot { it.id == id }
        }
    }

    fun downloadAttachment(id: Long) {
        val message = mutableMessages.value.firstOrNull { it.id == id } ?: return
        if (message.attachmentId == null || message.attachmentStatus == "downloading") return
        setAttachmentState(id, "downloading")
        scope.launch {
            var temporary: File? = null
            try {
                val url = requireNotNull(message.attachmentUrl)
                val wrappedKey = requireNotNull(message.attachmentKey)
                val iv = requireNotNull(message.attachmentIv)
                val expectedHash = requireNotNull(message.attachmentSha256)
                val directory = File(appContext.filesDir, "attachments/${message.attachmentId}").apply { mkdirs() }
                val safeName = message.attachmentName.orEmpty().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "attachment" }
                val destination = File(directory, safeName)
                val temporaryFile = File(directory, ".download")
                temporary = temporaryFile
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                try {
                    if (connection.responseCode !in 200..299) error("Download failed (HTTP ${connection.responseCode})")
                    val digest = AttachmentCrypto.sha256()
                    connection.inputStream.use { input ->
                        AttachmentCrypto.decryptingStream(input, wrappedKey, iv).use { decrypted ->
                            temporaryFile.outputStream().use { output ->
                                DigestOutputStream(output, digest).use { verified -> decrypted.copyTo(verified) }
                            }
                        }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHash.equals(expectedHash, ignoreCase = true)) error("File verification failed")
                    if (message.attachmentSize != null && temporaryFile.length() != message.attachmentSize) error("Downloaded file size did not match")
                    if (destination.exists()) destination.delete()
                    if (!temporaryFile.renameTo(destination)) error("Could not save downloaded file")
                    setAttachmentState(id, "ready", destination.absolutePath)
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                temporary?.delete()
                setAttachmentState(id, "failed", error = error.message ?: "Download failed")
            }
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
    private fun setAttachmentState(id: Long, status: String, filePath: String? = null, error: String? = null) {
        if (database.setAttachmentState(id, status, filePath, error)) {
            mutableMessages.value = mutableMessages.value.map {
                if (it.id == id) it.copy(attachmentStatus = status, attachmentPath = filePath, attachmentError = error) else it
            }
        }
    }

    @Synchronized
    fun refresh() {
        mutableMessages.value = database.all()
    }
}
