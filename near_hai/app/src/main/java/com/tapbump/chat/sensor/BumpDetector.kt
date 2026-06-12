package com.tapbump.chat.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 加速度计碰撞检测器
 * 检测短时间内加速度的剧烈变化来判断两部手机是否发生碰撞
 */
class BumpDetector(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "BumpDetector"

        // 碰撞检测阈值（加速度变化超过此值认为是碰撞）
        private const val BUMP_THRESHOLD = 15.0f // m/s²

        // 碰撞冷却时间（防止重复检测）
        private const val COOLDOWN_MS = 2000L

        // 加速度采样窗口（检测碰撞的时间窗口）
        private const val SAMPLE_WINDOW_MS = 100L
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val vibrator: Vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    private var lastBumpTime = 0L
    private var lastSampleTime = 0L
    private var lastMagnitude = 0f

    // 碰撞事件回调
    var onBumpDetected: (() -> Unit)? = null

    fun startMonitoring() {
        if (accelerometer == null) {
            Log.e(TAG, "设备不支持加速度计")
            return
        }
        sensorManager.registerListener(
            this, accelerometer,
            SensorManager.SENSOR_DELAY_GAME // 快速采样
        )
        _isMonitoring.value = true
        lastBumpTime = 0L
        Log.d(TAG, "碰撞检测已启动")
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        _isMonitoring.value = false
        Log.d(TAG, "碰撞检测已停止")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()

        // 冷却时间检查
        if (now - lastBumpTime < COOLDOWN_MS) return

        // 计算当前加速度的大小
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // 采样窗口检查
        if (lastSampleTime > 0 && (now - lastSampleTime) < SAMPLE_WINDOW_MS) {
            // 计算加速度变化
            val delta = Math.abs(magnitude - lastMagnitude)

            if (delta > BUMP_THRESHOLD) {
                Log.d(TAG, "检测到碰撞! 加速度变化: $delta")
                lastBumpTime = now

                // 震动反馈
                vibrate()

                // 触发碰撞回调
                onBumpDetected?.invoke()
            }
        }

        lastSampleTime = now
        lastMagnitude = magnitude
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // 不需要处理
    }

    private fun vibrate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }
}
