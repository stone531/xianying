package com.xianying.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import com.xianying.app.control.ExitExecutor
import com.xianying.app.runtime.Runtime
import com.xianying.app.session.SessionSnapshot
import com.xianying.app.session.Status
import com.xianying.app.ui.WarningActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务——限映的"心脏"。
 * 职责：① 每秒滴答驱动状态机计时 ② 常驻通知显示剩余额度
 *       ③ 分级戒断：剩 1 分钟顶部预警 → 额度用完全屏警告+震动+提示音 → 10 秒后返回桌面。
 * 前台服务 + 常驻通知是 Android 保活的正规手段（厂商 ROM 相对不杀带通知的前台服务）。
 */
class KeepAliveForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "xianying_monitor"
        private const val WARN_CHANNEL_ID = "xianying_warn"
        private const val NOTI_ID = 1
        private const val WARN_NOTI_ID = 2

        /** 服务是否在运行（主界面显示状态用）。 */
        @Volatile var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
        val noti = buildNotification("监控运行中")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }

        // 状态机 → 常驻通知文案
        Runtime.sessionManager.listener = { snap ->
            updateNotification(notificationText(snap))
            if (snap.status == Status.WARNING) {
                updateWarningNotification(snap.warningRemainingMs)   // 倒计时逐秒刷新
            } else {
                stopWarningVibration()
                cancelWarningNotification()     // 警告结束（退出/会话终止）即撤掉警告通知，不能赖着不走
            }
        }
        // 分级戒断 · 第一级：剩 1 分钟 → 顶部横幅预警（不遮挡、几秒自动消失）
        Runtime.sessionManager.warnListener = { showPreWarning() }
        // 分级戒断 · 第二级：额度用完 → 全屏警告 + 震动 + 提示音（10 秒缓冲开始）
        Runtime.sessionManager.warningListener = { showWarningBlast() }
        // 缓冲结束 → 返回桌面
        Runtime.sessionManager.exitListener = { ExitExecutor.exitToHome() }

        // 心跳：每秒滴答一次
        scope.launch {
            while (isActive) {
                Runtime.sessionManager.tick()
                delay(1_000)
            }
        }
    }

    private fun notificationText(snap: SessionSnapshot): String = when (snap.status) {
        Status.IDLE -> "监控运行中"
        Status.TIMING -> when {
            (snap.config?.maxVideos ?: 0) > 0 ->
                "已刷 ${snap.watchedVideos} / ${snap.config!!.maxVideos} 条"
            else ->
                "剩余 ${snap.remainingMs / 60_000} 分 ${snap.remainingMs % 60_000 / 1000} 秒"
        }
        Status.PAUSED -> "已暂停（剩余额度保留）"
        Status.WARNING ->
            "额度已用完 · ${(snap.warningRemainingMs + 999) / 1000} 秒后返回桌面"
    }

    /** 第一级预警：高优先级通知，顶部横幅数秒自动收起，不遮挡画面。 */
    private fun showPreWarning() {
        val n = Notification.Builder(this, WARN_CHANNEL_ID)
            .setContentTitle("限映 · 额度即将用完")
            .setContentText("剩余不足 1 分钟，准备收尾吧")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)                 // 自动消失，符合"轻量提示"规格
            .build()
        getSystemService(NotificationManager::class.java).notify(WARN_NOTI_ID, n)
    }

    /** 第二级全屏警告：全屏 Intent 拉起警告页 + 震动 + 提示音（fail-open：页面被拦也不影响到点退出）。 */
    private fun showWarningBlast() {
        val n = buildWarningNotification(10_000L, withFullScreenIntent = true)
        getSystemService(NotificationManager::class.java).notify(WARN_NOTI_ID + 1, n)

        startWarningVibration()
        playWarningTone()
    }

    /** 警告期每秒刷新倒计时文案（复用同一条通知，不重复弹横幅）。 */
    private fun updateWarningNotification(remainingMs: Long) {
        val n = buildWarningNotification(remainingMs, withFullScreenIntent = false)
        getSystemService(NotificationManager::class.java).notify(WARN_NOTI_ID + 1, n)
    }

    private fun cancelWarningNotification() {
        getSystemService(NotificationManager::class.java).cancel(WARN_NOTI_ID + 1)
    }

    private fun buildWarningNotification(remainingMs: Long, withFullScreenIntent: Boolean): Notification {
        val builder = Notification.Builder(this, WARN_CHANNEL_ID)
            .setContentTitle("限映 · 本次额度已用完")
            .setContentText("${(remainingMs + 999) / 1000} 秒后自动返回桌面")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
        if (withFullScreenIntent) {
            val fullScreen = PendingIntent.getActivity(
                this, 0,
                Intent(this, WarningActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(fullScreen)
            builder.setFullScreenIntent(fullScreen, true)   // 支持的 ROM 直接盖屏警告页
        }
        return builder.build()
    }

    /** 警告期震动：脉冲波形循环，状态离开 WARNING 时停止。 */
    private fun startWarningVibration() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator = v
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0))   // 0 = 循环
        } catch (e: Exception) {
            android.util.Log.e("XianyingEye", "vibrate failed", e)   // fail-open：震不动不影响退出
        }
    }

    private fun stopWarningVibration() {
        try { vibrator?.cancel() } catch (_: Exception) { }
        vibrator = null
    }

    /** 提示音：闹钟流短促鸣响（无权限要求；失败静默放行）。 */
    private fun playWarningTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 1_500)
        } catch (e: Exception) {
            android.util.Log.e("XianyingEye", "tone failed", e)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "限映监控", NotificationManager.IMPORTANCE_LOW)
        )
        // 警告通道：高优先级才弹横幅/全屏；震动交给代码波形，通道级震动关掉避免叠加
        nm.createNotificationChannel(
            NotificationChannel(WARN_CHANNEL_ID, "限映预警与警告", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("限映")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        stopWarningVibration()
        cancelWarningNotification()
        scope.cancel()
        Runtime.sessionManager.listener = null
        Runtime.sessionManager.warnListener = null
        Runtime.sessionManager.warningListener = null
        Runtime.sessionManager.exitListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
