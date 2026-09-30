package com.xianying.app.session

import com.xianying.app.model.SessionConfig
import com.xianying.app.model.TargetApp

/**
 * 会话状态机（纯 Kotlin，不依赖 Android，可在电脑上单测）。
 *
 * 事件入口：
 *  - onForegroundChanged(pkg)：无障碍服务回调"当前前台 App 变了"
 *  - tick()：前台服务每秒调用，推进时间
 *  - configure(quotaMs)：主界面设置本次额度（对下一次新会话生效）
 *
 * 核心规则（降级版 MVP）：
 *  - 进抖音且已有额度 → 开始/恢复倒计时
 *  - 离开抖音 → 暂停，剩余额度保留
 *  - 额度归零（必然发生在抖音前台期间）→ 触发 exitListener（执行返回桌面）→ 会话结束
 *  - 结束后无冷却：再进抖音按当前预设额度开新会话（产品红线：自主管控，不强制封锁）
 */
class SessionManager(private val clock: Clock) {

    /** 每次状态/快照变化回调（含每秒 tick），供通知栏与主界面刷新。 */
    var listener: ((SessionSnapshot) -> Unit)? = null

    /** 额度用尽时回调一次，由服务层执行"返回桌面"。 */
    var exitListener: (() -> Unit)? = null

    private var status = Status.IDLE
    private var quotaMs = 0L                 // 预设额度（分钟换算成毫秒）
    private var remainingMs = 0L             // 本会话剩余额度
    private var inTarget = false             // 当前前台是否为目标 App
    private var lastTickMs = clock.now()

    fun snapshot() = SessionSnapshot(
        status = status,
        config = if (status == Status.IDLE) null else SessionConfig(quotaMs),
        remainingMs = if (status == Status.TIMING || status == Status.PAUSED) remainingMs else 0L,
    )

    /** 主界面设置额度。仅对"下一次新会话"生效，不打断进行中的会话。 */
    fun configure(quotaMs: Long) {
        this.quotaMs = quotaMs
        if (quotaMs <= 0 && status == Status.IDLE) emit()   // 清零额度 → 关闭管控
    }

    /** pkg = 当前前台包名；目标之外任意值（含 null）都算"离开目标"。 */
    fun onForegroundChanged(pkg: String?) {
        val isTarget = TargetApp.fromPackage(pkg) != null
        if (isTarget == inTarget) return                     // 幂等：状态没变就不动
        inTarget = isTarget
        when {
            isTarget -> onEnterTarget()
            else -> onLeaveTarget()
        }
    }

    fun tick() {
        val now = clock.now()
        val dt = now - lastTickMs
        lastTickMs = now
        if (status == Status.TIMING) {
            remainingMs -= dt
            if (remainingMs <= 0) {
                remainingMs = 0
                status = Status.IDLE
                inTarget = false    // 会话已终结；此后任何抖音事件都视为"新会话"（无冷却红线）
                emit()
                exitListener?.invoke()                       // 服务层此刻执行返回桌面
                return
            }
        }
        emit()
    }

    private fun onEnterTarget() {
        when (status) {
            Status.IDLE ->
                if (quotaMs > 0) {                           // 无额度（=未设限/关闭）则保持 IDLE
                    remainingMs = quotaMs
                    status = Status.TIMING
                    emit()
                }
            Status.PAUSED -> {                               // 恢复会话：剩余额度保留
                status = Status.TIMING
                emit()
            }
            Status.TIMING -> Unit                            // 不会发生（inTarget 已变化）
        }
    }

    private fun onLeaveTarget() {
        if (status == Status.TIMING) {
            status = Status.PAUSED                           // 额度冻结
            emit()
        }
        // IDLE（无会话）离开目标：无需处理
    }

    private fun emit() { listener?.invoke(snapshot()) }
}
