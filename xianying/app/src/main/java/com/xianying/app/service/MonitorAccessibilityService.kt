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
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowState(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScroll(event)
        }
    }

    /** 窗口切换 = 前台 App 变化，报给状态机。 */
    private fun onWindowState(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (isOverlay(pkg)) return               // 非真前台的系统窗口，直接忽略
        log(pkg)
        android.util.Log.d("XianyingEye",
            "event pkg=$pkg isTarget=${Runtime.isTarget(pkg)} status=${Runtime.sessionManager.snapshot().status}")
        Runtime.sessionManager.onForegroundChanged(pkg)
        android.util.Log.d("XianyingEye",
            "after status=${Runtime.sessionManager.snapshot().status}")
    }

    /**
     * 滚动事件 = 用户在目标 App 里上下滑（短视频翻页）。
     * 一次物理滑动会产生一连串 SCROLL 事件，去抖 600ms 只当一次"切换视频"。
     * 已知局限（记录为系统/识别限制）：评论区、个人页等横向/局部滚动也会触发，
     * 可能多计条数；仅影响"仅条数"模式计数精度，不影响时长模式。
     */
    private fun onScroll(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (!Runtime.isTarget(pkg)) return       // 只关心目标 App 内的滑动
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScrollMs < SCROLL_DEBOUNCE_MS) return
        lastScrollMs = now
        android.util.Log.d("XianyingEye", "scroll pkg=$pkg -> videoChanged")
        Runtime.sessionManager.onVideoChanged()
    }

    companion object {
        /**
         * 最近 200 条事件环形日志。
         * 真机不便连线看不了 logcat，靠它在主界面排查"为什么没识别到"。
         */
        val recentEvents = ArrayDeque<String>()

        /**
         * "非真前台"的系统窗口名单——这些窗口出现不代表用户切换了 App：
         *  - 自己的包（含全屏警告页）
         *  - com.android.systemui：通知横幅、通知栏、快捷设置（额度预警横幅就是它！）
         *  - android：系统对话框（崩溃/ANR 弹窗）
         *  - 输入法（抖音评论打字时键盘弹出，人还在抖音）
         * 曾尝试用 getLaunchIntentForPackage==null 判断，但 Android 11+ 包可见性限制导致
         * 查任何第三方 App 都返回 null（连抖音都被误杀），且桌面本身也没有桌面图标，故弃用。
         */
        private val OVERLAY_PACKAGES = setOf("com.android.systemui", "android")

        /** 一次滑动的滚动事件去抖窗口（毫秒）。 */
        private const val SCROLL_DEBOUNCE_MS = 600L
        private var lastScrollMs = 0L

        private fun isOverlay(pkg: String): Boolean =
            pkg == "com.xianying.app" ||
                pkg in OVERLAY_PACKAGES ||
                pkg.contains("inputmethod") ||
                pkg.endsWith(".ime")

        private fun log(pkg: String?) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            recentEvents.addLast("$ts  ${pkg ?: "(null)"}")
            while (recentEvents.size > 200) recentEvents.removeFirst()
        }
    }

    override fun onInterrupt() { /* 系统中断无障碍服务时回调，无需处理 */ }

    override fun onDestroy() {
        Runtime.monitorService = null
        super.onDestroy()
    }
}
