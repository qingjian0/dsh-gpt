package com.gpt.hook.api

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable, caller-isolated conversation storage for the local DSH gateway.
 * Raw caller identifiers are never persisted; only a SHA-256 digest is stored.
 */
class ConversationRepository(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "dsh_conversations.db", null, 1
) {
    data class Conversation(
        val id: String,
        val title: String,
        val model: String,
        val parentId: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: JSONArray
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                id TEXT PRIMARY KEY NOT NULL,
                caller_hash TEXT NOT NULL,
                title TEXT NOT NULL,
                model TEXT NOT NULL,
                parent_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                messages TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX conversations_caller_updated ON conversations(caller_hash, updated_at DESC)")
        db.execSQL("CREATE INDEX conversations_parent ON conversations(parent_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations are added here; never silently drop conversation data.
    }

    fun create(callerId: String, title: String, model: String, parentId: String? = null): Conversation {
        val caller = callerHash(callerId)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        if (parentId != null && get(callerId, parentId) == null) {
            throw IllegalArgumentException("parent conversation not found")
        }
        val values = ContentValues().apply {
            put("id", id)
            put("caller_hash", caller)
            put("title", title.trim().take(160).ifBlank { "New conversation" })
            put("model", model.trim().take(120).ifBlank { "auto" })
            if (parentId == null) putNull("parent_id") else put("parent_id", parentId)
            put("created_at", now)
            put("updated_at", now)
            put("messages", "[]")
        }
        writableDatabase.insertOrThrow("conversations", null, values)
        return getById(id)!!
    }

    fun list(callerId: String, limit: Int = 100): List<Conversation> {
        val result = ArrayList<Conversation>()
        readableDatabase.query(
            "conversations", null, "caller_hash=?",
            arrayOf(callerHash(callerId)), null, null, "updated_at DESC", limit.coerceIn(1, 200).toString()
        ).use { c -> while (c.moveToNext()) result += c.toConversation() }
        return result
    }

    fun get(callerId: String, id: String): Conversation? {
        readableDatabase.query(
            "conversations", null, "id=? AND caller_hash=?",
            arrayOf(id, callerHash(callerId)), null, null, null, "1"
        ).use { c -> return if (c.moveToFirst()) c.toConversation() else null }
    }

    fun appendMessage(callerId: String, id: String, message: JSONObject): Conversation? {
        val serializedMessage = message.toString()
        require(serializedMessage.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "message exceeds 256 KiB"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = get(callerId, id) ?: return null
            require(current.messages.length() < MAX_MESSAGES_PER_CONVERSATION) {
                "conversation message limit reached"
            }
            val messages = JSONArray(current.messages.toString()).put(JSONObject(serializedMessage))
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("messages", messages.toString())
                put("updated_at", now)
            }
            db.update("conversations", values, "id=? AND caller_hash=?", arrayOf(id, callerHash(callerId)))
            db.setTransactionSuccessful()
            return current.copy(updatedAt = now, messages = messages)
        } finally {
            db.endTransaction()
        }
    }

    fun delete(callerId: String, id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Detach children before deleting the parent so lineage remains valid.
            db.execSQL(
                "UPDATE conversations SET parent_id=NULL WHERE parent_id=? AND caller_hash=?",
                arrayOf(id, callerHash(callerId))
            )
            val count = db.delete("conversations", "id=? AND caller_hash=?", arrayOf(id, callerHash(callerId)))
            db.setTransactionSuccessful()
            return count > 0
        } finally {
            db.endTransaction()
        }
    }

    private fun getById(id: String): Conversation? {
        readableDatabase.query("conversations", null, "id=?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) c.toConversation() else null
        }
    }

    private fun android.database.Cursor.toConversation() = Conversation(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        model = getString(getColumnIndexOrThrow("model")),
        parentId = getString(getColumnIndexOrThrow("parent_id")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        messages = JSONArray(getString(getColumnIndexOrThrow("messages")))
    )

    private fun callerHash(value: String): String {
        require(value.length in 8..256 && value.none { it.isWhitespace() }) { "X-DSH-Client-Id must be 8..256 non-whitespace characters" }
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun closeSafely() = close()

    private companion object {
        const val MAX_MESSAGES_PER_CONVERSATION = 500
        const val MAX_MESSAGE_BYTES = 256 * 1024
    }
}
