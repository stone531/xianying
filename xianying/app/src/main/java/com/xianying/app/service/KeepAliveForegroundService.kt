package com.xianying.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
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
    private var pausedByScreenOff = false

    /**
     * 息屏/解锁感知（review P0-1）：抖音内按电源键后系统往往不发窗口事件（keyguard 是
     * systemui 且已被过滤），会话会一路 TIMING 到额度耗尽——口袋里亮屏+震动+响铃。
     * 息屏=脱离（暂停）；解锁=按息屏前在刷的 App 回放恢复（若解锁后实际在桌面，
     * 随后的窗口事件会在 1 秒级内纠正为 PAUSED）。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val st = Runtime.sessionManager.snapshot().status
                    if (st == Status.TIMING) {
                        pausedByScreenOff = true
                        Runtime.sessionManager.onForegroundChanged(null)
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (pausedByScreenOff) {
                        pausedByScreenOff = false
                        Runtime.sessionManager.onForegroundChanged(Runtime.lastTargetPkg)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
        startForegroundWith(notiText = "监控运行中")

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
        // 分级戒断 · 第一级：剩 1 分钟 / 剩最后 1 条 → 顶部横幅预警（几秒自动消失）
        Runtime.sessionManager.warnListener = {
            val countMode = (Runtime.sessionManager.snapshot().config?.maxVideos ?: 0) > 0
            showPreWarning(
                if (countMode) "还剩最后 1 条" else "剩余不足 1 分钟，准备收尾吧"
            )
        }
        // 分级戒断 · 第二级：额度用完 → 全屏警告 + 震动 + 提示音（10 秒缓冲开始）
        Runtime.sessionManager.warningListener = { showWarningBlast() }
        // 缓冲结束 → 返回桌面；失败（无障碍掉线）→ 修复提醒通知，不能静默失效
        Runtime.sessionManager.exitListener = {
            if (!ExitExecutor.exitToHome()) showRepairNotification()
        }

        // 心跳：每秒滴答一次
        scope.launch {
            while (isActive) {
                Runtime.sessionManager.tick()
                delay(1_000)
            }
        }

        // 息屏/解锁广播（protected system broadcast，注册无需导出标志也兼容 33+）
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundWith(notificationText(Runtime.sessionManager.snapshot()))
        return START_STICKY                            // 进程被杀后系统尝试重启服务（review P2-1）
    }

    private fun startForegroundWith(notiText: String) {
        val noti = buildNotification(notiText)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
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
    private fun showPreWarning(text: String) {
        val n = Notification.Builder(this, WARN_CHANNEL_ID)
            .setContentTitle("限映 · 额度即将用完")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)                 // 自动消失，符合"轻量提示"规格
            .build()
        getSystemService(NotificationManager::class.java).notify(WARN_NOTI_ID, n)
    }

    /** 返回桌面执行失败（无障碍服务掉线）时的修复提醒——fail-open 契约的"提醒修复"半句。 */
    private fun showRepairNotification() {
        val openA11y = PendingIntent.getActivity(
            this, 1,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, WARN_CHANNEL_ID)
            .setContentTitle("限映 · 监控已失效")
            .setContentText("额度已用完但未能返回桌面，请点此检查无障碍服务")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openA11y)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WARN_NOTI_ID + 2, n)
    }

    /** 第二级全屏警告：全屏 Intent 拉起警告页 + 震动 + 提示音（fail-open：页面被拦也不影响到点退出）。 */
    private fun showWarningBlast() {
        getSystemService(NotificationManager::class.java).cancel(WARN_NOTI_ID)   // 预警横幅让位给全屏警告
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

    /** 提示音：闹钟流短促鸣响（无权限要求；失败静默放行；响完释放资源）。 */
    private fun playWarningTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 1_500)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 2_000)
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
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        scope.cancel()
        Runtime.sessionManager.listener = null
        Runtime.sessionManager.warnListener = null
        Runtime.sessionManager.warningListener = null
        Runtime.sessionManager.exitListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
