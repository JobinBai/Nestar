package com.nestar.finder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 提醒引擎（只看"有没有扫到"，与距离/强度无关）：
 *  - 只要扫描到目标设备（收到它的广播）→ 立即进入持续震动；
 *  - 每 1.5 秒震一阵，只要还扫得到就一直震；
 *  - 搜不到信号（离开范围）→ 停止震动。
 */
class AlarmEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    @Volatile var enabled: Boolean = false
    @Volatile var thresholdRssi: Int = -68      // dBm，提醒阈值（由界面灵敏度滑杆写入）
    private val HYSTERESIS_DB = 5               // 迟滞回差(dB)，防止阈值附近抖动导致反复提醒

    private val _state = MutableStateFlow(AlarmState.IDLE)
    val state: StateFlow<AlarmState> = _state.asStateFlow()

    enum class AlarmState { IDLE, ALARMING, DISARMED }

    private var job: Job? = null

    fun startWatching(rssiFlow: kotlinx.coroutines.flow.StateFlow<Double?>) {
        job?.cancel()
        job = scope.launch {
            var lastBuzzMs = 0L
            var alarming = false
            while (true) {
                val rssi = rssiFlow.value
                // 迟滞回差：进入提醒要求 rssi >= 阈值；已提醒后需低于 (阈值-回差) 才退出，
                // 防止信号在阈值附近抖动时反复进出提醒（震动/提示音狂刷）
                val enter = rssi != null && rssi >= thresholdRssi
                val stay = rssi != null && rssi >= (thresholdRssi - HYSTERESIS_DB)
                val present = enabled && (if (alarming) stay else enter)

                if (!enabled) {
                    if (alarming) { stopVibrate(); cancelNotification() }
                    alarming = false
                    _state.value = AlarmState.DISARMED
                    delay(300); continue
                }

                if (present) {
                    // 信号强于阈值：进入持续震动
                    if (!alarming) {
                        alarming = true
                        lastBuzzMs = System.currentTimeMillis()
                        buzzPulse()
                        notify("进入提醒范围", "目标信号 ${rssi?.toInt()} dBm（阈值 ${thresholdRssi}），持续震动提醒中")
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBuzzMs >= 1500) {
                            buzzPulse()
                            lastBuzzMs = now
                        }
                    }
                    _state.value = AlarmState.ALARMING
                } else {
                    // 信号弱于阈值 / 搜不到：停止
                    if (alarming) {
                        stopVibrate()
                        cancelNotification()
                    }
                    alarming = false
                    _state.value = AlarmState.IDLE
                }
                delay(300)
            }
        }
    }

    fun stopWatching() {
        job?.cancel(); job = null
        stopVibrate()
        cancelNotification()   // 关闭时同时清掉提醒通知，避免残留在通知栏
        _state.value = AlarmState.DISARMED
    }

    private fun stopVibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            }
        } catch (_: Exception) {}
    }

    private fun cancelNotification() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(1001)
        } catch (_: Exception) {}
    }

    private fun buzzPulse() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 350), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350), -1))
                else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 350), -1)
            }
        } catch (_: Exception) {}
    }

    private fun notify(title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "nestar_alarm"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "近安星提醒", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notif: Notification = NotificationCompat.Builder(context, chId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(1001, notif)
    }
}
