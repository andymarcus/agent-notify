package com.agentnotify.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MessageDatabase(context: Context) :
    SQLiteOpenHelper(context, "agent-notify.db", null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                topic TEXT NOT NULL,
                body TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                attachment_id TEXT,
                attachment_name TEXT,
                attachment_mime TEXT,
                attachment_size INTEGER,
                attachment_url TEXT,
                attachment_key TEXT,
                attachment_iv TEXT,
                attachment_sha256 TEXT,
                attachment_status TEXT,
                attachment_path TEXT,
                attachment_error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX messages_topic_idx ON messages(topic)")
        db.execSQL("CREATE INDEX messages_received_idx ON messages(received_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Messages seen before read tracking existed should not all become unread.
            db.execSQL("ALTER TABLE messages ADD COLUMN is_read INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_name TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_mime TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_size INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_url TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_key TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_iv TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_sha256 TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_status TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_path TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_error TEXT")
        }
    }

    fun insert(topic: String, body: String, sentAt: Long, receivedAt: Long, attachment: AttachmentPayload?): Long {
        val values = ContentValues().apply {
            put("topic", topic)
            put("body", body)
            put("sent_at", sentAt)
            put("received_at", receivedAt)
            put("is_read", 0)
            attachment?.let {
                put("attachment_id", it.id)
                put("attachment_name", it.name)
                put("attachment_mime", it.mime)
                put("attachment_size", it.size)
                put("attachment_url", it.url)
                put("attachment_key", it.key)
                put("attachment_iv", it.iv)
                put("attachment_sha256", it.sha256)
                put("attachment_status", "offered")
            }
        }
        return writableDatabase.insertOrThrow("messages", null, values)
    }

    fun all(): List<AgentMessage> {
        val result = mutableListOf<AgentMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "topic", "body", "sent_at", "received_at", "is_read", "attachment_id", "attachment_name", "attachment_mime", "attachment_size", "attachment_url", "attachment_key", "attachment_iv", "attachment_sha256", "attachment_status", "attachment_path", "attachment_error"),
            null,
            null,
            null,
            null,
            "received_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AgentMessage(
                    id = cursor.getLong(0),
                    topic = cursor.getString(1),
                    body = cursor.getString(2),
                    sentAt = cursor.getLong(3),
                    receivedAt = cursor.getLong(4),
                    isRead = cursor.getInt(5) != 0,
                    attachmentId = cursor.getString(6),
                    attachmentName = cursor.getString(7),
                    attachmentMime = cursor.getString(8),
                    attachmentSize = if (cursor.isNull(9)) null else cursor.getLong(9),
                    attachmentUrl = cursor.getString(10),
                    attachmentKey = cursor.getString(11),
                    attachmentIv = cursor.getString(12),
                    attachmentSha256 = cursor.getString(13),
                    attachmentStatus = cursor.getString(14),
                    attachmentPath = cursor.getString(15),
                    attachmentError = cursor.getString(16),
                )
            }
        }
        return result
    }

    fun setRead(id: Long, isRead: Boolean): Boolean {
        val values = ContentValues().apply { put("is_read", if (isRead) 1 else 0) }
        return writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString())) > 0
    }

    fun setAttachmentState(id: Long, status: String, filePath: String? = null, error: String? = null): Boolean {
        val values = ContentValues().apply {
            put("attachment_status", status)
            if (filePath == null) putNull("attachment_path") else put("attachment_path", filePath)
            if (error == null) putNull("attachment_error") else put("attachment_error", error)
        }
        return writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString())) > 0
    }

    fun delete(id: Long): Boolean =
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString())) > 0

    companion object {
        private const val DATABASE_VERSION = 3
    }
}

data class AttachmentPayload(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val url: String,
    val key: String,
    val iv: String,
    val sha256: String,
)
