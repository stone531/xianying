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
 * 核心规则（降级版 MVP + 30 分钟脱离解除 + 分级戒断）：
 *  - 进抖音且已有额度 → 开始/恢复倒计时
 *  - 离开抖音 → 暂停，剩余额度保留，同时开始累计脱离时长
 *  - 脱离中途切回抖音 → 脱离计时清零，沿用本次剩余额度
 *  - 脱离累计满 30 分钟 → 会话自动销毁（下次进入 = 全新会话拿满额度；人不在目标 App，不触发退出动作）
 *  - 剩余额度降至预警线（默认 1 分钟）→ warnListener 触发一次（顶部轻量提示，不遮挡）
 *  - 额度归零 → 进入 WARNING 缓冲期（默认 10 秒；warningListener 触发全屏警告+震动+提示音）
 *  - 警告期结束 → exitListener（返回桌面）→ 会话结束；警告期内用户自己离开 → 会话结束，不触发退出
 *  - 结束后无冷却：再进抖音按当前预设额度开新会话（产品红线：自主管控，不强制封锁）
 */
class SessionManager(
    private val clock: Clock,
    /** 目标判定函数，默认按 TargetApp 包名表；可注入替身包名用于模拟器测试。 */
    private val isTargetPackage: (String?) -> Boolean = { TargetApp.fromPackage(it) != null },
    /** 脱离自动解除阈值（规格固定 30 分钟；构造参数化便于测试与后续调档）。 */
    private val awayLimitMs: Long = 30 * 60_000L,
    /** 提前预警线：剩余额度降到此值触发一次预警（规格固定 1 分钟）。 */
    private val warnAtMs: Long = 60_000L,
    /** WARNING 警告期时长（规格固定 10 秒缓冲）。 */
    private val warningMs: Long = 10_000L,
) {

    /** 每次状态/快照变化回调（含每秒 tick），供通知栏与主界面刷新。 */
    var listener: ((SessionSnapshot) -> Unit)? = null

    /** 剩余额度触及预警线时回调一次（每会话一次），服务层发顶部轻提示。 */
    var warnListener: (() -> Unit)? = null

    /** 进入 WARNING 警告期时回调一次，服务层弹全屏警告+震动+提示音。 */
    var warningListener: (() -> Unit)? = null

    /** 警告期结束、额度正式用尽时回调一次，由服务层执行"返回桌面"。 */
    var exitListener: (() -> Unit)? = null

    private var status = Status.IDLE
    private var quotaMs = 0L                 // 预设额度（分钟换算成毫秒）
    private var remainingMs = 0L             // 本会话剩余额度
    private var inTarget = false             // 当前前台是否为目标 App
    private var awayMs = 0L                  // 本次脱离已累计时长（切回目标即清零）
    private var warningTriggered = false     // 本会话预警是否已触发过（防重复）
    private var warningRemainingMs = 0L      // WARNING 警告期剩余毫秒
    private var lastTickMs = clock.now()

    fun snapshot() = SessionSnapshot(
        status = status,
        config = if (status == Status.IDLE) null else SessionConfig(quotaMs),
        remainingMs = if (status == Status.TIMING || status == Status.PAUSED) remainingMs else 0L,
        warningRemainingMs = if (status == Status.WARNING) warningRemainingMs else 0L,
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
        warningRemainingMs = 0
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

    /**
     * 推进时间。dt 可能跨越多个阶段边界（如一次 tick 从计时期跨进警告期再到退出），
     * 因此按阶段分段消耗，保证任意大小的 dt 状态流转都正确（真实心跳 1 秒一跳，测试会大跳步）。
     */
    fun tick() {
        var dt = clock.now() - lastTickMs
        lastTickMs += dt
        while (dt > 0) {
            val consumed = when (status) {
                Status.TIMING -> {
                    val step = minOf(dt, remainingMs)               // 最多用到归零
                    remainingMs -= step
                    dt -= step
                    if (remainingMs <= 0L) {                        // 额度归零 → 分级戒断缓冲期
                        remainingMs = 0L
                        status = Status.WARNING
                        warningRemainingMs = warningMs
                        emit()
                        warningListener?.invoke()                  // 全屏警告 + 震动 + 提示音
                    } else if (!warningTriggered && remainingMs <= warnAtMs) {  // 提前预警（每会话一次）
                        warningTriggered = true
                        warnListener?.invoke()
                    }
                    step
                }
                Status.WARNING -> {
                    val step = minOf(dt, warningRemainingMs)       // 最多到警告期结束
                    warningRemainingMs -= step
                    dt -= step
                    if (warningRemainingMs <= 0L) {                 // 缓冲结束 → 正式退出
                        warningRemainingMs = 0L
                        status = Status.IDLE
                        inTarget = false   // 会话已终结；此后任何抖音事件都视为"新会话"（无冷却红线）
                        awayMs = 0
                        emit()
                        exitListener?.invoke()                     // 服务层此刻执行返回桌面
                    }
                    step
                }
                Status.PAUSED -> {
                    val step = minOf(dt, awayLimitMs - awayMs)     // 最多到脱离解除线
                    awayMs += step
                    dt -= step
                    if (awayMs >= awayLimitMs) {                    // 满 30 分钟：会话自动解除
                        status = Status.IDLE
                        remainingMs = 0
                        awayMs = 0
                        // 人已不在目标 App，无需执行返回桌面（不触发 exitListener）
                    }
                    step
                }
                Status.IDLE -> dt.also { dt = 0 }                   // 无会话：整段消耗掉
            }
            if (consumed <= 0L) break                               // 防御：避免异常状态下死循环
        }
        emit()
    }

    private fun onEnterTarget() {
        awayMs = 0                                           // 切回目标：脱离计时清零（规格）
        when (status) {
            Status.IDLE ->
                if (quotaMs > 0) {                           // 无额度（=未设限/关闭）则保持 IDLE
                    remainingMs = quotaMs
                    warningTriggered = false                 // 新会话：预警标志复位
                    status = Status.TIMING
                    emit()
                }
            Status.PAUSED -> {                               // 恢复会话：剩余额度保留
                status = Status.TIMING
                emit()
            }
            Status.TIMING, Status.WARNING -> Unit            // 不会发生（inTarget 已变化 / 警告期无重进）
        }
    }

    private fun onLeaveTarget() {
        when (status) {
            Status.TIMING -> {
                status = Status.PAUSED                       // 额度冻结
                emit()
            }
            Status.WARNING -> {                              // 警告期内用户自己走了：
                status = Status.IDLE                         // 额度已用完，会话就地结束，
                warningRemainingMs = 0                       // 不再触发返回桌面（人已离开）
                emit()
            }
            Status.IDLE, Status.PAUSED -> Unit               // 离开目标无需处理
        }
    }

    private fun emit() { listener?.invoke(snapshot()) }
}
