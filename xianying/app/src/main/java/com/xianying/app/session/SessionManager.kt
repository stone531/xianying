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
 * 核心规则（降级版 MVP + 30 分钟脱离解除）：
 *  - 进抖音且已有额度 → 开始/恢复倒计时
 *  - 离开抖音 → 暂停，剩余额度保留，同时开始累计脱离时长
 *  - 脱离中途切回抖音 → 脱离计时清零，沿用本次剩余额度
 *  - 脱离累计满 30 分钟 → 会话自动销毁（下次进入 = 全新会话拿满额度；人不在目标 App，不触发退出动作）
 *  - 额度归零（必然发生在抖音前台期间）→ 触发 exitListener（执行返回桌面）→ 会话结束
 *  - 结束后无冷却：再进抖音按当前预设额度开新会话（产品红线：自主管控，不强制封锁）
 */
class SessionManager(
    private val clock: Clock,
    /** 目标判定函数，默认按 TargetApp 包名表；可注入替身包名用于模拟器测试。 */
    private val isTargetPackage: (String?) -> Boolean = { TargetApp.fromPackage(it) != null },
    /** 脱离自动解除阈值（规格固定 30 分钟；构造参数化便于测试与后续调档）。 */
    private val awayLimitMs: Long = 30 * 60_000L,
) {

    /** 每次状态/快照变化回调（含每秒 tick），供通知栏与主界面刷新。 */
    var listener: ((SessionSnapshot) -> Unit)? = null

    /** 额度用尽时回调一次，由服务层执行"返回桌面"。 */
    var exitListener: (() -> Unit)? = null

    private var status = Status.IDLE
    private var quotaMs = 0L                 // 预设额度（分钟换算成毫秒）
    private var remainingMs = 0L             // 本会话剩余额度
    private var inTarget = false             // 当前前台是否为目标 App
    private var awayMs = 0L                  // 本次脱离已累计时长（切回目标即清零）
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

    /** 总开关关闭时调用：立即终止当前会话（剩余额度作废），回到 IDLE。 */
    fun reset() {
        status = Status.IDLE
        remainingMs = 0
        inTarget = false
        awayMs = 0
        emit()
    }

    /** pkg = 当前前台包名；目标之外任意值（含 null）都算"离开目标"。 */
    fun onForegroundChanged(pkg: String?) {
        val isTarget = isTargetPackage(pkg)
        println("XianyingSM: onForegroundChanged pkg=$pkg isTarget=$isTarget inTarget=$inTarget status=$status quota=$quotaMs")
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
        when (status) {
            Status.TIMING -> {
                remainingMs -= dt
                if (remainingMs <= 0) {
                    remainingMs = 0
                    status = Status.IDLE
                    inTarget = false    // 会话已终结；此后任何抖音事件都视为"新会话"（无冷却红线）
                    awayMs = 0
                    emit()
                    exitListener?.invoke()                   // 服务层此刻执行返回桌面
                    return
                }
            }
            Status.PAUSED -> {
                awayMs += dt                                        // 脱离计时只在暂停期累计
                if (awayMs >= awayLimitMs) {                        // 满 30 分钟：会话自动解除
                    status = Status.IDLE
                    remainingMs = 0
                    awayMs = 0
                    // 人已不在目标 App，无需执行返回桌面（不触发 exitListener）
                }
            }
            Status.IDLE -> Unit
        }
        emit()
    }

    private fun onEnterTarget() {
        awayMs = 0                                           // 切回目标：脱离计时清零（规格）
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
