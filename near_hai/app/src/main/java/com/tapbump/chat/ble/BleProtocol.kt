package com.tapbump.chat.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.tapbump.chat.TapBumpApplication
import com.tapbump.chat.crypto.CryptoManager
import com.tapbump.chat.data.DatabaseHelper
import com.tapbump.chat.data.Friend
import com.tapbump.chat.data.Message
import com.tapbump.chat.util.PreferenceManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

/**
 * BLE 协议层 — 统一管理广播、扫描、GATT连接、数据交换
 */
class BleProtocol(private val context: Context) {

    companion object {
        private const val TAG = "BleProtocol"

        // GATT Service & Characteristic UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val CHAR_FRIEND_EXCHANGE_UUID: UUID = UUID.fromString("0000abce-0000-1000-8000-00805f9b34fb")
        val CHAR_MESSAGE_UUID: UUID = UUID.fromString("0000abcf-0000-1000-8000-00805f9b34fb")

        // 广播中的服务数据 UUID (用于携带昵称+公钥哈希)
        val ADVERTISE_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
    }

    // 事件流
    private val _scanResults = MutableSharedFlow<BleDeviceInfo>(replay = 0)
    val scanResults: SharedFlow<BleDeviceInfo> = _scanResults

    private val _friendExchangeResult = MutableSharedFlow<FriendExchangeResult>(replay = 0)
    val friendExchangeResult: SharedFlow<FriendExchangeResult> = _friendExchangeResult

    private val _incomingMessage = MutableSharedFlow<ReceivedMessage>(replay = 0)
    val incomingMessage: SharedFlow<ReceivedMessage> = _incomingMessage

    private val _collisionDetected = MutableStateFlow(false)
    val collisionDetected: StateFlow<Boolean> = _collisionDetected

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    // 蓝牙组件
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeGatt: BluetoothGatt? = null

    private val app = TapBumpApplication.instance
    private val cryptoManager: CryptoManager = app.cryptoManager
    private val prefs: PreferenceManager = app.preferenceManager
    private val db: DatabaseHelper = app.databaseHelper

    // ========================
    // BLE 广播
    // ========================

    fun startAdvertising() {
        if (!bluetoothAdapter.isEnabled) return
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // 将昵称和公钥哈希打包到 Service Data 中
        val payload = JSONObject().apply {
            put("n", prefs.getNickname())
            put("h", cryptoManager.getMyPublicKeyHash())
        }.toString()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ADVERTISE_SERVICE_UUID, payload.toByteArray(Charsets.UTF_8))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            _isAdvertising.value = true
            Log.d(TAG, "BLE 广播已启动")
        } catch (e: SecurityException) {
            Log.e(TAG, "启动广播失败: 缺少权限", e)
        }
    }

    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {}
        _isAdvertising.value = false
        Log.d(TAG, "BLE 广播已停止")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "广播启动成功")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "广播启动失败: $errorCode")
            _isAdvertising.value = false
        }
    }

    // ========================
    // BLE 扫描
    // ========================

    fun startScanning(durationMs: Long = 5000L) {
        if (!bluetoothAdapter.isEnabled) return
        scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ADVERTISE_SERVICE_UUID)
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner?.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            Log.d(TAG, "BLE 扫描已启动，持续 ${durationMs}ms")

            // 自动停止扫描
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopScanning()
            }, durationMs)
        } catch (e: SecurityException) {
            Log.e(TAG, "启动扫描失败: 缺少权限", e)
        }
    }

    fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
        _isScanning.value = false
        Log.d(TAG, "BLE 扫描已停止")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val record = result.scanRecord ?: return

            val serviceData = record.serviceData[ADVERTISE_SERVICE_UUID] ?: return
            try {
                val json = JSONObject(String(serviceData, Charsets.UTF_8))
                val nickname = json.optString("n", "未知")
                val publicKeyHash = json.optString("h", "")

                if (publicKeyHash.isEmpty()) return
                // 不扫描自己
                if (publicKeyHash == cryptoManager.getMyPublicKeyHash()) return

                val deviceInfo = BleDeviceInfo(
                    device = device,
                    nickname = nickname,
                    publicKeyHash = publicKeyHash,
                    rssi = result.rssi
                )
                _scanResults.tryEmit(deviceInfo)
                Log.d(TAG, "扫描到设备: $nickname, hash=$publicKeyHash, rssi=${result.rssi}")
            } catch (e: Exception) {
                Log.e(TAG, "解析广播数据失败", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败: $errorCode")
            _isScanning.value = false
        }
    }

    // ========================
    // GATT 服务端
    // ========================

    fun startGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            // 好友交换特征 — Write + Read
            val friendChar = BluetoothGattCharacteristic(
                CHAR_FRIEND_EXCHANGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(friendChar)

            // 消息特征 — Write + Notify
            val messageChar = BluetoothGattCharacteristic(
                CHAR_MESSAGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(messageChar)

            gattServer?.addService(service)
            Log.d(TAG, "GATT 服务端已启动")
        } catch (e: SecurityException) {
            Log.e(TAG, "启动 GATT 服务端失败", e)
        }
    }

    fun stopGattServer() {
        try {
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "设备已连接(服务端): ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "设备已断开(服务端): ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            try {
                if (characteristic.uuid == CHAR_FRIEND_EXCHANGE_UUID) {
                    // 返回自己的完整资料
                    val profile = JSONObject().apply {
                        put("deviceId", prefs.getDeviceId())
                        put("nickname", prefs.getNickname())
                        put("avatarIndex", prefs.getAvatarIndex())
                        put("publicKey", cryptoManager.getMyPublicKey())
                    }.toString()

                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_SUCCESS, offset,
                        profile.toByteArray(Charsets.UTF_8)
                    )
                } else {
                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_FAILURE, 0, null
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "读取特征失败", e)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            try {
                when (characteristic.uuid) {
                    CHAR_FRIEND_EXCHANGE_UUID -> {
                        // 收到对方的完整资料
                        val jsonStr = String(value, Charsets.UTF_8)
                        val json = JSONObject(jsonStr)
                        val friend = Friend(
                            deviceId = json.getString("deviceId"),
                            nickname = json.getString("nickname"),
                            avatarIndex = json.optInt("avatarIndex", 0),
                            publicKey = json.getString("publicKey"),
                            publicKeyHash = "" // 将根据公钥计算
                        )

                        _friendExchangeResult.tryEmit(
                            FriendExchangeResult.Success(friend, device)
                        )
                        Log.d(TAG, "收到好友资料: ${friend.nickname}")
                    }

                    CHAR_MESSAGE_UUID -> {
                        // 收到加密消息
                        val encryptedStr = String(value, Charsets.UTF_8)
                        try {
                            val decrypted = cryptoManager.decrypt(encryptedStr)
                            val msgJson = JSONObject(decrypted)
                            val senderId = msgJson.getString("senderId")
                            val content = msgJson.getString("content")
                            val timestamp = msgJson.getLong("timestamp")

                            _incomingMessage.tryEmit(
                                ReceivedMessage(
                                    senderId = senderId,
                                    content = content,
                                    timestamp = timestamp,
                                    fromDevice = device
                                )
                            )
                            Log.d(TAG, "收到解密消息: $content")
                        } catch (e: Exception) {
                            Log.e(TAG, "消息解密失败", e)
                        }
                    }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_SUCCESS, 0, null
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "写入特征失败", e)
            }
        }
    }

    // ========================
    // GATT 客户端 — 连接并交换数据
    // ========================

    fun connectAndExchange(device: BluetoothDevice) {
        try {
            activeGatt?.close()
            activeGatt = device.connectGatt(context, false, gattClientCallback)
            Log.d(TAG, "正在连接设备: ${device.address}")
        } catch (e: SecurityException) {
            Log.e(TAG, "连接设备失败", e)
        }
    }

    fun sendMessageViaGatt(device: BluetoothDevice, message: Message) {
        try {
            if (activeGatt == null || activeGatt?.device?.address != device.address) {
                activeGatt?.close()
                activeGatt = device.connectGatt(context, false, gattClientCallback)
                // 需要等待连接成功后再发送 — 简化处理
                Log.d(TAG, "重新连接以发送消息")
                return
            }

            val service = activeGatt?.getService(SERVICE_UUID) ?: return
            val messageChar = service.getCharacteristic(CHAR_MESSAGE_UUID) ?: return

            // 构造消息 JSON，用对方公钥加密
            val friend = db.getFriendByDeviceId(message.receiverId) ?: return
            val msgJson = JSONObject().apply {
                put("senderId", prefs.getDeviceId())
                put("content", message.content)
                put("timestamp", message.timestamp)
            }.toString()

            val encrypted = cryptoManager.encrypt(msgJson, friend.publicKey)
            messageChar.value = encrypted.toByteArray(Charsets.UTF_8)

            activeGatt?.writeCharacteristic(messageChar)
            Log.d(TAG, "消息已通过 GATT 发送")
        } catch (e: SecurityException) {
            Log.e(TAG, "发送消息失败", e)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT 客户端连接成功: ${gatt.device.address}")
                try {
                    activeGatt?.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(TAG, "发现服务失败", e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT 客户端断开: ${gatt.device.address}")
                activeGatt?.close()
                activeGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "服务发现完成")
                try {
                    // 读取对方完整资料
                    val service = gatt.getService(SERVICE_UUID) ?: return
                    val friendChar = service.getCharacteristic(CHAR_FRIEND_EXCHANGE_UUID) ?: return
                    activeGatt?.readCharacteristic(friendChar)
                } catch (e: SecurityException) {
                    Log.e(TAG, "读取特征失败", e)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                characteristic.uuid == CHAR_FRIEND_EXCHANGE_UUID
            ) {
                try {
                    val jsonStr = String(characteristic.value, Charsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    val friend = Friend(
                        deviceId = json.getString("deviceId"),
                        nickname = json.getString("nickname"),
                        avatarIndex = json.optInt("avatarIndex", 0),
                        publicKey = json.getString("publicKey"),
                        publicKeyHash = ""
                    )
                    _friendExchangeResult.tryEmit(
                        FriendExchangeResult.Success(friend, gatt.device)
                    )
                    Log.d(TAG, "读取到好友资料: ${friend.nickname}")
                } catch (e: Exception) {
                    Log.e(TAG, "解析好友资料失败", e)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "特征写入完成: ${characteristic.uuid}, status=$status")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_MESSAGE_UUID) {
                try {
                    val encryptedStr = String(characteristic.value, Charsets.UTF_8)
                    val decrypted = cryptoManager.decrypt(encryptedStr)
                    val msgJson = JSONObject(decrypted)
                    val senderId = msgJson.getString("senderId")
                    val content = msgJson.getString("content")
                    val timestamp = msgJson.getLong("timestamp")

                    _incomingMessage.tryEmit(
                        ReceivedMessage(
                            senderId = senderId,
                            content = content,
                            timestamp = timestamp,
                            fromDevice = gatt.device
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "通知消息解密失败", e)
                }
            }
        }
    }

    // ========================
    // 碰撞检测控制
    // ========================

    fun onCollisionDetected() {
        _collisionDetected.value = true
        // 碰撞后立即扫描5秒
        startScanning(5000L)
    }

    fun resetCollision() {
        _collisionDetected.value = false
    }

    // ========================
    // 清理
    // ========================

    fun cleanup() {
        stopAdvertising()
        stopScanning()
        try {
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
        stopGattServer()
    }

    // ========================
    // 工具方法
    // ========================

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter.isEnabled
}

// ========================
// 数据类
// ========================

data class BleDeviceInfo(
    val device: BluetoothDevice,
    val nickname: String,
    val publicKeyHash: String,
    val rssi: Int
)

sealed class FriendExchangeResult {
    data class Success(val friend: Friend, val device: BluetoothDevice) : FriendExchangeResult()
    data class Error(val message: String) : FriendExchangeResult()
}

data class ReceivedMessage(
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val fromDevice: BluetoothDevice
)
