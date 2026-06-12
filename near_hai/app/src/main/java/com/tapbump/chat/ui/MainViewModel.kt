package com.tapbump.chat.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapbump.chat.TapBumpApplication
import com.tapbump.chat.ble.BleDeviceInfo
import com.tapbump.chat.ble.BleProtocol
import com.tapbump.chat.ble.FriendExchangeResult
import com.tapbump.chat.data.Friend
import com.tapbump.chat.data.Message
import com.tapbump.chat.sensor.BumpDetector
import com.tapbump.chat.service.BluetoothForegroundService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TapBumpApplication
    private val db = app.databaseHelper
    private val prefs = app.preferenceManager
    private val cryptoManager = app.cryptoManager

    val bleProtocol = BleProtocol(application)
    val bumpDetector = BumpDetector(application)

    // ========== 好友列表 ==========

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends

    // ========== 扫描到的设备 ==========

    private val _discoveredDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDeviceInfo>> = _discoveredDevices

    // ========== 碰撞状态 ==========

    val isBumpMode: StateFlow<Boolean> = bumpDetector.isMonitoring

    // ========== 添加好友结果 ==========

    private val _addFriendResult = MutableSharedFlow<String>()
    val addFriendResult: SharedFlow<String> = _addFriendResult

    // ========== 自己的信息 ==========

    val myNickname: String get() = prefs.getNickname()
    val myAvatarIndex: Int get() = prefs.getAvatarIndex()
    val myDeviceId: String get() = prefs.getDeviceId()

    init {
        // 首次启动初始化
        if (prefs.isFirstLaunch()) {
            cryptoManager.generateKeyPairIfNeeded()
            prefs.setFirstLaunchDone()
        }

        // 加载好友列表
        refreshFriends()

        // 监听扫描结果
        viewModelScope.launch {
            bleProtocol.scanResults.collect { device ->
                val current = _discoveredDevices.value.toMutableList()
                // 去重
                if (current.none { it.publicKeyHash == device.publicKeyHash }) {
                    current.add(device)
                    _discoveredDevices.value = current
                }
            }
        }

        // 监听碰撞
        bumpDetector.onBumpDetected = {
            bleProtocol.onCollisionDetected()
        }

        // 监听好友交换结果
        viewModelScope.launch {
            bleProtocol.friendExchangeResult.collect { result ->
                when (result) {
                    is FriendExchangeResult.Success -> {
                        // 计算公钥哈希并保存好友
                        val publicKey = cryptoManager.loadPublicKey(result.friend.publicKey)
                        val hash = cryptoManager.computePublicKeyHash(publicKey)
                        val friend = result.friend.copy(publicKeyHash = hash)
                        db.addFriend(friend)
                        refreshFriends()
                        _addFriendResult.emit("成功添加好友: ${friend.nickname}")
                    }

                    is FriendExchangeResult.Error -> {
                        _addFriendResult.emit("添加失败: ${result.message}")
                    }
                }
            }
        }

        // 监听收到的消息
        viewModelScope.launch {
            bleProtocol.incomingMessage.collect { msg ->
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

    // ========== 好友操作 ==========

    fun refreshFriends() {
        _friends.value = db.getAllFriends()
    }

    fun getLastMessageTime(friendDeviceId: String): Long {
        return db.getLastMessageTimestamp(friendDeviceId, prefs.getDeviceId())
    }

    fun deleteFriend(friend: Friend) {
        db.deleteFriend(friend.deviceId)
        refreshFriends()
    }

    // ========== 碰一碰模式 ==========

    fun startBumpMode() {
        if (!bleProtocol.isBluetoothEnabled()) {
            _addFriendResult.tryEmit("请先开启蓝牙")
            return
        }

        // 启动 BLE 广播
        bleProtocol.startAdvertising()
        bleProtocol.startGattServer()

        // 启动碰撞检测
        bumpDetector.startMonitoring()

        _discoveredDevices.value = emptyList()
    }

    fun stopBumpMode() {
        bleProtocol.stopAdvertising()
        bumpDetector.stopMonitoring()
        bleProtocol.stopScanning()
    }

    fun connectToDevice(deviceInfo: BleDeviceInfo) {
        bleProtocol.connectAndExchange(deviceInfo.device)
    }

    // ========== 前台服务 ==========

    fun startForegroundService() {
        val context = getApplication<Application>()
        val intent = Intent(context, BluetoothForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopForegroundService() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, BluetoothForegroundService::class.java))
    }

    // ========== 蓝牙状态 ==========

    fun isBluetoothEnabled(): Boolean = bleProtocol.isBluetoothEnabled()

    fun enableBluetooth(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return if (adapter != null && !adapter.isEnabled) {
            // 用户需要在系统设置中开启或通过 Intent 请求
            false
        } else {
            true
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleProtocol.cleanup()
        bumpDetector.stopMonitoring()
    }
}
