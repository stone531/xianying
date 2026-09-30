package com.xianying.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.xianying.app.runtime.Runtime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 无障碍监控服务——限映的"眼睛"。
 * 系统每次窗口切换（打开/切换 App）都会推送事件到这里；
 * 我们只做一件事：把前台包名报给状态机。
 */
class MonitorAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 最近 200 条事件环形日志。
         * 真机不便连线看不了 logcat，靠它在主界面排查"为什么没识别到"。
         */
        val recentEvents = ArrayDeque<String>()

        private fun log(pkg: String?) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            recentEvents.addLast("$ts  ${pkg ?: "(null)"}")
            while (recentEvents.size > 200) recentEvents.removeFirst()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Runtime.monitorService = this
        // 自愈：进程被系统杀掉后，只要无障碍仍启用，系统会重新绑定本服务。
        // 借这个时机恢复设置，并按总开关状态重新拉起前台服务（倒计时心脏），
        // 避免"总开关开着但监控悄悄失效"。恢复是幂等的，主界面启动时也会做一遍。
        try {
            val master = Runtime.restoreFromPrefs(this)
            if (master && !KeepAliveForegroundService.running) {
                KeepAliveForegroundService.start(this)
            }
        } catch (e: Exception) {
            // 自愈失败不影响事件监听本身（fail-open：工具故障不妨碍正常用机）
            android.util.Log.e("XianyingEye", "self-heal failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return          // 忽略自己界面的窗口事件
        log(pkg)
        android.util.Log.d("XianyingEye",
            "event pkg=$pkg isTarget=${Runtime.isTarget(pkg)} status=${Runtime.sessionManager.snapshot().status}")
        Runtime.sessionManager.onForegroundChanged(pkg)
        android.util.Log.d("XianyingEye",
            "after status=${Runtime.sessionManager.snapshot().status}")
    }

    override fun onInterrupt() { /* 系统中断无障碍服务时回调，无需处理 */ }

    override fun onDestroy() {
        Runtime.monitorService = null
        super.onDestroy()
    }
}
