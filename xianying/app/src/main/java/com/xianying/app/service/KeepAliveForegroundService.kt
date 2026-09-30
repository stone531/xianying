package com.xianying.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.xianying.app.control.ExitExecutor
import com.xianying.app.runtime.Runtime
import com.xianying.app.session.SessionSnapshot
import com.xianying.app.session.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务——限映的"心脏"。
 * 职责：① 每秒滴答驱动状态机计时 ② 常驻通知显示剩余额度 ③ 额度到点执行返回桌面。
 * 前台服务 + 常驻通知是 Android 保活的正规手段（厂商 ROM 相对不杀带通知的前台服务）。
 */
class KeepAliveForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "xianying_monitor"
        private const val NOTI_ID = 1

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

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        val noti = buildNotification("监控运行中")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }

        // 状态机 → 常驻通知文案
        Runtime.sessionManager.listener = { snap ->
            updateNotification(notificationText(snap))
        }
        // 额度到点 → 返回桌面
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
        Status.TIMING -> "剩余 ${snap.remainingMs / 60_000} 分 ${snap.remainingMs % 60_000 / 1000} 秒"
        Status.PAUSED -> "已暂停（剩余额度保留）"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "限映监控", NotificationManager.IMPORTANCE_LOW)
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
        scope.cancel()
        Runtime.sessionManager.listener = null
        Runtime.sessionManager.exitListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
