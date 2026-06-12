package com.tapbump.chat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tapbump.chat.data.DatabaseHelper
import com.tapbump.chat.crypto.CryptoManager
import com.tapbump.chat.util.PreferenceManager

class TapBumpApplication : Application() {

    lateinit var databaseHelper: DatabaseHelper
        private set
    lateinit var cryptoManager: CryptoManager
        private set
    lateinit var preferenceManager: PreferenceManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)
        databaseHelper = DatabaseHelper(this)
        cryptoManager = CryptoManager(preferenceManager)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_BT_SERVICE,
                "蓝牙服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "蓝牙后台服务运行中"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_BT_SERVICE = "bluetooth_service_channel"
        const val NOTIFICATION_BT_SERVICE_ID = 1001

        lateinit var instance: TapBumpApplication
            private set
    }
}
