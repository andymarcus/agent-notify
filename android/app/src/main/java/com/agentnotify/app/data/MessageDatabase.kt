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
                is_read INTEGER NOT NULL DEFAULT 0
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
    }

    fun insert(topic: String, body: String, sentAt: Long, receivedAt: Long): Long {
        val values = ContentValues().apply {
            put("topic", topic)
            put("body", body)
            put("sent_at", sentAt)
            put("received_at", receivedAt)
            put("is_read", 0)
        }
        return writableDatabase.insertOrThrow("messages", null, values)
    }

    fun all(): List<AgentMessage> {
        val result = mutableListOf<AgentMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "topic", "body", "sent_at", "received_at", "is_read"),
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
                )
            }
        }
        return result
    }

    fun setRead(id: Long, isRead: Boolean): Boolean {
        val values = ContentValues().apply { put("is_read", if (isRead) 1 else 0) }
        return writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString())) > 0
    }

    fun delete(id: Long): Boolean =
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString())) > 0

    companion object {
        private const val DATABASE_VERSION = 2
    }
}
