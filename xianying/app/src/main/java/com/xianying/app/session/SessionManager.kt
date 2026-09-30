package com.xianying.app.session

import com.xianying.app.model.SessionConfig
import com.xianying.app.model.TargetApp

/**
 * 会话状态机（纯 Kotlin，不依赖 Android，可在电脑上单测）。
 *
 * 事件入口：
 *  - onForegroundChanged(pkg)：无障碍服务回调"当前前台 App 变了"
 *  - onVideoChanged()：无障碍服务回调"检测到视频切换"（滑动翻页）
 *  - tick()：前台服务每秒调用，推进时间
 *  - configure(quotaMs, maxVideos)：主界面设置额度（对下一次新会话生效）
 *
 * 核心规则（降级版 MVP + 30 分钟脱离解除 + 分级戒断 + 仅条数模式）：
 *  - 进抖音且已有额度（时长或条数任一 >0）→ 开始/恢复倒计时
 *  - 离开抖音 → 暂停，额度保留，同时开始累计脱离时长
 *  - 脱离中途切回抖音 → 脱离计时清零，沿用本次剩余额度
 *  - 脱离累计满 30 分钟 → 会话自动销毁（下次进入 = 全新会话拿满额度；人不在目标 App，不触发退出动作）
 *  - 剩余时长降至预警线（默认 1 分钟）→ warnListener 触发一次（顶部轻量提示，不遮挡）
 *  - 额度归零（时长）或有效条数达标（条数）→ 进入 WARNING 缓冲期（默认 10 秒；warningListener 触发全屏警告+震动+提示音）
 *  - 有效条数：单条视频累计观看 ≥5 秒（validVideoMs）后发生切换才计 1 条（规格）
 *  - 警告期结束 → exitListener（返回桌面）→ 会话结束；警告期内任何"离开"事件被忽略
 *  - 结束后无冷却：再进抖音按当前预设额度开新会话（产品红线：自主管控，不强制封锁）
 */
class SessionManager(
    private val clock: Clock,
    /** 目标判定函数，默认按 TargetApp 包名表；可注入替身包名用于模拟器测试。 */
    private val isTargetPackage: (String?) -> Boolean = { TargetApp.fromPackage(it) != null },
    /** 脱离自动解除阈值（规格固定 30 分钟；构造参数化便于测试与后续调档）。 */
    private val awayLimitMs: Long = 30 * 60_000L,
    /** 提前预警线：剩余时长降到此值触发一次预警（规格固定 1 分钟；条数模式无时长预警）。 */
    private val warnAtMs: Long = 60_000L,
    /** WARNING 警告期时长（规格固定 10 秒缓冲）。 */
    private val warningMs: Long = 10_000L,
    /** 有效观看判定：单条视频累计观看满此时长才计 1 条（规格固定 5 秒）。 */
    private val validVideoMs: Long = 5_000L,
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
    private var quotaMs = 0L                 // 预设时长额度（毫秒）；0 = 不限时长
    private var maxVideos = 0                // 预设条数额度；0 = 不限条数
    private var remainingMs = 0L             // 本会话剩余时长额度
    private var watchedVideos = 0            // 本会话已计有效条数
    private var videoAccumMs = 0L            // 当前这条视频已累计观看毫秒（≥validVideoMs 才算有效）
    private var inTarget = false             // 当前前台是否为目标 App
    private var awayMs = 0L                  // 本次脱离已累计时长（切回目标即清零）
    private var warningTriggered = false     // 本会话预警是否已触发过（防重复）
    private var warningRemainingMs = 0L      // WARNING 警告期剩余毫秒
    private var lastTickMs = clock.now()

    @Synchronized fun snapshot() = SessionSnapshot(
        status = status,
        config = if (status == Status.IDLE) null else SessionConfig(quotaMs, maxVideos),
        remainingMs = if (status == Status.TIMING || status == Status.PAUSED) remainingMs else 0L,
        warningRemainingMs = if (status == Status.WARNING) warningRemainingMs else 0L,
        watchedVideos = if (status == Status.IDLE) 0 else watchedVideos,
    )


    /** 主界面设置额度（时长毫秒 + 条数，任一 >0 即设限）。仅对"下一次新会话"生效。 */
    @Synchronized fun configure(quotaMs: Long, maxVideos: Int = 0) {
        this.quotaMs = quotaMs
        this.maxVideos = maxVideos
        if (quotaMs <= 0 && maxVideos <= 0 && status == Status.IDLE) emit()   // 全零 → 关闭管控
    }

    /** 总开关关闭时调用：立即终止当前会话（剩余额度作废），回到 IDLE。 */
    @Synchronized fun reset() {
        status = Status.IDLE
        remainingMs = 0
        inTarget = false
        awayMs = 0
        warningRemainingMs = 0
        watchedVideos = 0
        videoAccumMs = 0
        emit()
    }

    /** pkg = 当前前台包名；目标之外任意值（含 null）都算"离开目标"。 */
    @Synchronized fun onForegroundChanged(pkg: String?) {
        val isTarget = isTargetPackage(pkg)
        println("XianyingSM: onForegroundChanged pkg=$pkg isTarget=$isTarget inTarget=$inTarget status=$status quota=$quotaMs")
        if (isTarget == inTarget) return                     // 幂等：状态没变就不动
        // 警告期（10 秒缓冲）任何"离开"事件都忽略：全屏警告通知自身的 systemui 横幅、
        // 系统对话框都会产生窗口事件，若当作离开会把 WARNING 打断成 IDLE，退出动作永远不执行
        //（真机踩坑 2026-09-30）。缓冲期必须雷打不动走完并执行返回桌面。
        if (status == Status.WARNING && !isTarget) return
        inTarget = isTarget
        when {
            isTarget -> onEnterTarget()
            else -> onLeaveTarget()
        }
    }

    /**
     * 视频切换事件（无障碍服务检测到滑动翻页）。
     * 规格：本条视频累计观看 ≥5 秒才计 1 条有效；快划不足 5 秒不计。
     * 达到条数额度 → 与时长耗尽同样进入 WARNING 缓冲期。
     */
    @Synchronized fun onVideoChanged() {
        if (status != Status.TIMING || !inTarget) return     // 只在目标 App 内观看时才可能切视频
        if (videoAccumMs >= validVideoMs) watchedVideos++
        videoAccumMs = 0
        if (maxVideos > 0 && watchedVideos >= maxVideos) {   // 条数达标 → 分级戒断
            enterWarning()
            return
        }
        emit()
    }

    /**
     * 推进时间。dt 可能跨越多个阶段边界（如一次 tick 从计时期跨进警告期再到退出），
     * 因此按阶段分段消耗，保证任意大小的 dt 状态流转都正确（真实心跳 1 秒一跳，测试会大跳步）。
     */
    @Synchronized fun tick() {
        var dt = clock.now() - lastTickMs
        lastTickMs += dt
        while (dt > 0) {
            val consumed = when (status) {
                Status.TIMING -> {
                    // 时长模式最多用到归零；条数模式不限时长（remainingMs=0）则整段消耗
                    val step = if (quotaMs > 0) minOf(dt, remainingMs) else dt
                    remainingMs -= step
                    videoAccumMs += step                      // 当前视频累计观看（切走/暂停即停）
                    dt -= step
                    if (quotaMs > 0 && remainingMs <= 0L) {   // 时长额度归零 → 分级戒断缓冲期
                        enterWarning()
                    } else if (quotaMs > 0 && !warningTriggered && remainingMs <= warnAtMs) {
                        warningTriggered = true               // 提前预警（每会话一次）
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
                        watchedVideos = 0
                        videoAccumMs = 0
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
                        watchedVideos = 0
                        videoAccumMs = 0
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

    /** 进入 WARNING 警告期（时长归零与条数达标共用路径）。 */
    private fun enterWarning() {
        if (quotaMs > 0) remainingMs = 0L
        status = Status.WARNING
        warningRemainingMs = warningMs
        emit()
        warningListener?.invoke()                  // 全屏警告 + 震动 + 提示音
    }

    private fun onEnterTarget() {
        awayMs = 0                                           // 切回目标：脱离计时清零（规格）
        when (status) {
            Status.IDLE ->
                if (quotaMs > 0 || maxVideos > 0) {          // 时长/条数任一设限才建会话
                    remainingMs = quotaMs
                    watchedVideos = 0                        // 新会话：计数清零、拿满额度
                    videoAccumMs = 0
                    warningTriggered = false                 // 新会话：预警标志复位
                    status = Status.TIMING
                    emit()
                }
            Status.PAUSED -> {                               // 恢复会话：剩余额度/已看条数保留
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
            // WARNING 不会到达这里（onForegroundChanged 已在警告期拦截离开事件）
            Status.IDLE, Status.PAUSED, Status.WARNING -> Unit
        }
    }

    private fun emit() { listener?.invoke(snapshot()) }
}
