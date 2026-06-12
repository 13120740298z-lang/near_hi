package com.tapbump.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Friend(
    val id: Long = 0,
    val deviceId: String,
    val nickname: String,
    val avatarIndex: Int,
    val publicKey: String,
    val publicKeyHash: String,
    val addedTime: Long = System.currentTimeMillis()
)

data class Message(
    val id: Long = 0,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isDelivered: Boolean = false
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "tapbump_chat.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_FRIENDS = "friends"
        const val TABLE_MESSAGES = "messages"

        const val COL_ID = "id"
        const val COL_DEVICE_ID = "device_id"
        const val COL_NICKNAME = "nickname"
        const val COL_AVATAR_INDEX = "avatar_index"
        const val COL_PUBLIC_KEY = "public_key"
        const val COL_PUBLIC_KEY_HASH = "public_key_hash"
        const val COL_ADDED_TIME = "added_time"

        const val COL_SENDER_ID = "sender_id"
        const val COL_RECEIVER_ID = "receiver_id"
        const val COL_CONTENT = "content"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_IS_DELIVERED = "is_delivered"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_FRIENDS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DEVICE_ID TEXT NOT NULL UNIQUE,
                $COL_NICKNAME TEXT NOT NULL,
                $COL_AVATAR_INDEX INTEGER DEFAULT 0,
                $COL_PUBLIC_KEY TEXT NOT NULL,
                $COL_PUBLIC_KEY_HASH TEXT NOT NULL,
                $COL_ADDED_TIME INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SENDER_ID TEXT NOT NULL,
                $COL_RECEIVER_ID TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IS_DELIVERED INTEGER DEFAULT 0
            )
        """)

        db.execSQL("CREATE INDEX idx_messages_sender ON $TABLE_MESSAGES($COL_SENDER_ID)")
        db.execSQL("CREATE INDEX idx_messages_receiver ON $TABLE_MESSAGES($COL_RECEIVER_ID)")
        db.execSQL("CREATE INDEX idx_messages_timestamp ON $TABLE_MESSAGES($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FRIENDS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        onCreate(db)
    }

    // ========== 好友操作 ==========

    fun addFriend(friend: Friend): Long {
        val db = writableDatabase
        // 如果已存在则更新
        val existing = getFriendByDeviceId(friend.deviceId)
        if (existing != null) {
            val values = ContentValues().apply {
                put(COL_NICKNAME, friend.nickname)
                put(COL_AVATAR_INDEX, friend.avatarIndex)
                put(COL_PUBLIC_KEY, friend.publicKey)
                put(COL_PUBLIC_KEY_HASH, friend.publicKeyHash)
            }
            db.update(TABLE_FRIENDS, values, "$COL_DEVICE_ID = ?", arrayOf(friend.deviceId))
            return existing.id
        }
        val values = ContentValues().apply {
            put(COL_DEVICE_ID, friend.deviceId)
            put(COL_NICKNAME, friend.nickname)
            put(COL_AVATAR_INDEX, friend.avatarIndex)
            put(COL_PUBLIC_KEY, friend.publicKey)
            put(COL_PUBLIC_KEY_HASH, friend.publicKeyHash)
            put(COL_ADDED_TIME, friend.addedTime)
        }
        return db.insert(TABLE_FRIENDS, null, values)
    }

    fun getFriendByDeviceId(deviceId: String): Friend? {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TABLE_FRIENDS, null,
            "$COL_DEVICE_ID = ?", arrayOf(deviceId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToFriend(it) else null
        }
    }

    fun getFriendByPublicKeyHash(hash: String): Friend? {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TABLE_FRIENDS, null,
            "$COL_PUBLIC_KEY_HASH = ?", arrayOf(hash),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToFriend(it) else null
        }
    }

    fun getAllFriends(): List<Friend> {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TABLE_FRIENDS, null, null, null,
            null, null, "$COL_ADDED_TIME DESC"
        )
        val friends = mutableListOf<Friend>()
        cursor.use {
            while (it.moveToNext()) {
                friends.add(cursorToFriend(it))
            }
        }
        return friends
    }

    fun deleteFriend(deviceId: String) {
        val db = writableDatabase
        db.delete(TABLE_FRIENDS, "$COL_DEVICE_ID = ?", arrayOf(deviceId))
    }

    // ========== 消息操作 ==========

    fun insertMessage(message: Message): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SENDER_ID, message.senderId)
            put(COL_RECEIVER_ID, message.receiverId)
            put(COL_CONTENT, message.content)
            put(COL_TIMESTAMP, message.timestamp)
            put(COL_IS_DELIVERED, if (message.isDelivered) 1 else 0)
        }
        return db.insert(TABLE_MESSAGES, null, values)
    }

    fun getMessages(senderId: String, receiverId: String): List<Message> {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TABLE_MESSAGES, null,
            "($COL_SENDER_ID = ? AND $COL_RECEIVER_ID = ?) OR ($COL_SENDER_ID = ? AND $COL_RECEIVER_ID = ?)",
            arrayOf(senderId, receiverId, receiverId, senderId),
            null, null,
            "$COL_TIMESTAMP ASC"
        )
        val messages = mutableListOf<Message>()
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages
    }

    fun getUndeliveredMessages(): List<Message> {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TABLE_MESSAGES, null,
            "$COL_IS_DELIVERED = 0", null,
            null, null,
            "$COL_TIMESTAMP ASC"
        )
        val messages = mutableListOf<Message>()
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages
    }

    fun markDelivered(messageId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_DELIVERED, 1)
        }
        db.update(TABLE_MESSAGES, values, "$COL_ID = ?", arrayOf(messageId.toString()))
    }

    fun getLastMessageTimestamp(friendDeviceId: String, myDeviceId: String): Long {
        val db = readableDatabase
        val cursor: Cursor = db.rawQuery(
            "SELECT MAX($COL_TIMESTAMP) FROM $TABLE_MESSAGES WHERE " +
                    "($COL_SENDER_ID = ? AND $COL_RECEIVER_ID = ?) OR ($COL_SENDER_ID = ? AND $COL_RECEIVER_ID = ?)",
            arrayOf(myDeviceId, friendDeviceId, friendDeviceId, myDeviceId)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else 0
        }
    }

    // ========== 游标转换 ==========

    private fun cursorToFriend(cursor: Cursor): Friend {
        return Friend(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEVICE_ID)),
            nickname = cursor.getString(cursor.getColumnIndexOrThrow(COL_NICKNAME)),
            avatarIndex = cursor.getInt(cursor.getColumnIndexOrThrow(COL_AVATAR_INDEX)),
            publicKey = cursor.getString(cursor.getColumnIndexOrThrow(COL_PUBLIC_KEY)),
            publicKeyHash = cursor.getString(cursor.getColumnIndexOrThrow(COL_PUBLIC_KEY_HASH)),
            addedTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ADDED_TIME))
        )
    }

    private fun cursorToMessage(cursor: Cursor): Message {
        return Message(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            senderId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER_ID)),
            receiverId = cursor.getString(cursor.getColumnIndexOrThrow(COL_RECEIVER_ID)),
            content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
            isDelivered = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_DELIVERED)) == 1
        )
    }
}
