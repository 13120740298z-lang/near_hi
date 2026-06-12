package com.tapbump.chat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapbump.chat.TapBumpApplication
import com.tapbump.chat.ble.BleProtocol
import com.tapbump.chat.data.Friend
import com.tapbump.chat.data.Message
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TapBumpApplication
    private val db = app.databaseHelper
    private val prefs = app.preferenceManager
    private val cryptoManager = app.cryptoManager

    private val bleProtocol = BleProtocol(application)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _sendStatus = MutableSharedFlow<SendStatus>()
    val sendStatus: SharedFlow<SendStatus> = _sendStatus

    private var currentFriend: Friend? = null

    fun loadFriend(deviceId: String): Friend? {
        val friend = db.getFriendByDeviceId(deviceId)
        currentFriend = friend
        return friend
    }

    fun loadMessages(friendDeviceId: String) {
        viewModelScope.launch {
            _messages.value = db.getMessages(prefs.getDeviceId(), friendDeviceId)
        }
    }

    fun sendMessage(content: String) {
        val friend = currentFriend ?: return

        if (content.length > 200) {
            _sendStatus.tryEmit(SendStatus.Error("消息不能超过200字符"))
            return
        }

        val myId = prefs.getDeviceId()
        val timestamp = System.currentTimeMillis()

        // 尝试加密消息
        val encryptedContent = try {
            val msgJson = org.json.JSONObject().apply {
                put("senderId", myId)
                put("content", content)
                put("timestamp", timestamp)
            }.toString()
            cryptoManager.encrypt(msgJson, friend.publicKey)
        } catch (e: Exception) {
            _sendStatus.tryEmit(SendStatus.Error("加密失败: ${e.message}"))
            return
        }

        // 先存入数据库，标记为未送达
        val message = Message(
            senderId = myId,
            receiverId = friend.deviceId,
            content = content,
            timestamp = timestamp,
            isDelivered = false
        )
        val msgId = db.insertMessage(message)

        // 刷新消息列表
        loadMessages(friend.deviceId)

        // 尝试通过蓝牙发送
        // 这里需要设备对象，通过 BLE 扫描获取
        // 实际中需要维护设备地址映射
        _sendStatus.tryEmit(SendStatus.Sent(msgId, false))
    }

    fun retrySend(message: Message) {
        // 重试发送未送达的消息
        val friend = currentFriend ?: return
        // 需要重新建立 GATT 连接
        // bleProtocol.sendMessageViaGatt(...)
    }

    fun refreshMessages() {
        currentFriend?.let { loadMessages(it.deviceId) }
    }

    fun getMyDeviceId(): String = prefs.getDeviceId()

    override fun onCleared() {
        super.onCleared()
        bleProtocol.cleanup()
    }
}

sealed class SendStatus {
    data class Sent(val messageId: Long, val delivered: Boolean) : SendStatus()
    data class Error(val message: String) : SendStatus()
}
