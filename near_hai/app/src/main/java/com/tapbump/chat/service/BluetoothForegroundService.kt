package com.tapbump.chat.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tapbump.chat.TapBumpApplication
import com.tapbump.chat.ble.BleProtocol
import com.tapbump.chat.ble.FriendExchangeResult
import com.tapbump.chat.ble.ReceivedMessage
import com.tapbump.chat.data.Message
import com.tapbump.chat.ui.MainActivity
import kotlinx.coroutines.*

/**
 * 蓝牙前台服务 — 后台保持 BLE 广播 + 扫描 + 消息接收
 */
class BluetoothForegroundService : Service() {

    companion object {
        private const val TAG = "BtForegroundService"
        private const val SCAN_INTERVAL_MS = 15_000L // 每15秒扫描一次
        private const val SCAN_DURATION_MS = 5_000L   // 每次扫描5秒
    }

    private lateinit var bleProtocol: BleProtocol
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    private var messageJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        bleProtocol = BleProtocol(this)

        // 启动 GATT 服务端
        bleProtocol.startGattServer()

        // 启动 BLE 广播
        bleProtocol.startAdvertising()

        // 监听收到的消息
        startMessageListener()

        // 定期扫描周围设备
        startPeriodicScanning()

        // 监听好友交换结果
        startFriendExchangeListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(TapBumpApplication.NOTIFICATION_BT_SERVICE_ID, notification)
        Log.d(TAG, "前台服务已启动")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "服务销毁")
        scanJob?.cancel()
        messageJob?.cancel()
        serviceScope.cancel()
        bleProtocol.cleanup()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TapBumpApplication.CHANNEL_BT_SERVICE)
            .setContentTitle("碰一碰聊天")
            .setContentText("蓝牙服务运行中，可发现附近设备")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 定期扫描周围设备
     */
    private fun startPeriodicScanning() {
        scanJob = serviceScope.launch {
            while (isActive) {
                delay(SCAN_INTERVAL_MS)
                if (bleProtocol.isBluetoothEnabled()) {
                    bleProtocol.startScanning(SCAN_DURATION_MS)
                    Log.d(TAG, "周期性扫描已触发")
                }
            }
        }
    }

    /**
     * 监听收到的消息
     */
    private fun startMessageListener() {
        messageJob = serviceScope.launch {
            bleProtocol.incomingMessage.collect { msg ->
                Log.d(TAG, "收到消息: ${msg.content}")
                val app = TapBumpApplication.instance
                val prefs = app.preferenceManager
                val db = app.databaseHelper

                // 存储消息
                val message = Message(
                    senderId = msg.senderId,
                    receiverId = prefs.getDeviceId(),
                    content = msg.content,
                    timestamp = msg.timestamp,
                    isDelivered = true
                )
                db.insertMessage(message)
            }
        }
    }

    /**
     * 监听好友交换结果
     */
    private fun startFriendExchangeListener() {
        serviceScope.launch {
            bleProtocol.friendExchangeResult.collect { result ->
                if (result is FriendExchangeResult.Success) {
                    val app = TapBumpApplication.instance
                    val db = app.databaseHelper
                    val cryptoManager = app.cryptoManager

                    // 计算公钥哈希
                    val publicKey = cryptoManager.loadPublicKey(result.friend.publicKey)
                    val hash = cryptoManager.computePublicKeyHash(publicKey)

                    val friend = result.friend.copy(publicKeyHash = hash)
                    db.addFriend(friend)
                    Log.d(TAG, "好友已保存: ${friend.nickname}")
                }
            }
        }
    }

    /**
     * 重试发送未送达的消息
     */
    fun retryPendingMessages() {
        serviceScope.launch {
            val app = TapBumpApplication.instance
            val db = app.databaseHelper
            val pending = db.getUndeliveredMessages()

            for (msg in pending) {
                val friend = db.getFriendByDeviceId(msg.receiverId) ?: continue
                // TODO: 需要通过某种方式获取对方的 BluetoothDevice 对象
                // 这里简化处理，实际需要建立 GATT 连接
                Log.d(TAG, "重试发送消息 id=${msg.id}")
            }
        }
    }
}
