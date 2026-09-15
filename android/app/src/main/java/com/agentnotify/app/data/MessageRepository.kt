package com.agentnotify.app.data

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
            message?.attachmentPath?.takeUnless { it.startsWith("content:") }?.let { path ->
                runCatching { File(path).parentFile?.deleteRecursively() }
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
                val directory = File(appContext.cacheDir, "attachments/${message.attachmentId}").apply { mkdirs() }
                val safeName = message.attachmentName.orEmpty().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "attachment" }
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
                    val downloadUri = saveToDownloads(temporaryFile, safeName, message.attachmentMime)
                    temporaryFile.delete()
                    setAttachmentState(id, "ready", downloadUri)
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

    private fun saveToDownloads(source: File, name: String, mime: String?): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                "Could not create file in Downloads"
            }
            try {
                requireNotNull(resolver.openOutputStream(uri)) { "Could not write file in Downloads" }.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                return uri.toString()
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists() && !downloads.mkdirs()) error("Could not create Downloads folder")
        val destination = File(downloads, name)
        source.copyTo(destination, overwrite = true)
        return destination.absolutePath
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
