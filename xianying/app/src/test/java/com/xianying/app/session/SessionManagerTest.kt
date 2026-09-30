package com.xianying.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SessionManager 状态机全路径测试（纯 JVM，无需设备）。
 * 规则来源：设计文档第 0 节修订版 MVP + 第 0.5 节到点行为决策。
 */
class SessionManagerTest {

    private lateinit var clock: FakeClock
    private lateinit var m: SessionManager

    @Before fun setup() {
        clock = FakeClock()
        m = SessionManager(clock)
    }

    /** 拨时间 + tick（模拟前台服务每秒驱动）。 */
    private fun tick(ms: Long) { clock.advance(ms); m.tick() }

    // ---- 建会话 ----

    @Test fun enterTarget_withQuota_startsTiming() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        val s = m.snapshot()
        assertEquals(Status.TIMING, s.status)
        assertEquals(5 * 60_000L, s.remainingMs)
    }

    @Test fun enterTarget_withoutQuota_staysIdle() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        assertEquals(Status.IDLE, m.snapshot().status)   // 未设额度 = 不管控
    }

    @Test fun quotaZero_neverStarts() {
        m.configure(0L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        assertEquals(Status.IDLE, m.snapshot().status)
    }

    @Test fun nonTargetPackage_ignored() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.tencent.mm")          // 微信
        assertEquals(Status.IDLE, m.snapshot().status)
    }

    // ---- 计时与暂停 ----

    @Test fun timing_decrementsOnlyWhileInTarget() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)                                     // 前台 1 分钟
        assertEquals(4 * 60_000L, m.snapshot().remainingMs)
    }

    @Test fun leaveTarget_pauses_quotaFrozen() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)
        m.onForegroundChanged("com.tencent.mm")          // 切到微信
        assertEquals(Status.PAUSED, m.snapshot().status)
        tick(10 * 60_000)                                // 离开 10 分钟
        assertEquals(Status.PAUSED, m.snapshot().status)
        assertEquals(4 * 60_000L, m.snapshot().remainingMs)   // 额度没被扣
    }

    @Test fun returnToTarget_resumesRemainingQuota() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)
        m.onForegroundChanged(null)                      // 回桌面
        tick(29 * 60_000)                                // 脱离 29 分钟（< 30 分钟，会话仍保留）
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        val s = m.snapshot()
        assertEquals(Status.TIMING, s.status)
        assertEquals(4 * 60_000L, s.remainingMs)         // 剩余额度保留
    }

    // ---- 30 分钟脱离自动解除（规格：脱离满 30 分钟，本次会话管控自动失效） ----

    @Test fun awayReaches30min_sessionReleased_nextEntryNewSession() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)                                     // 用掉 1 分钟，剩 4 分钟
        m.onForegroundChanged("com.tencent.mm")          // 脱离开始
        tick(29 * 60_000)
        assertEquals(Status.PAUSED, m.snapshot().status) // 29 分钟：还在保留
        tick(60_000)                                     // 脱离累计满 30 分钟
        assertEquals(Status.IDLE, m.snapshot().status)   // 会话自动销毁
        assertEquals(0L, m.snapshot().remainingMs)
        m.onForegroundChanged("com.ss.android.ugc.aweme")   // 再进入 = 全新会话
        val s = m.snapshot()
        assertEquals(Status.TIMING, s.status)
        assertEquals(5 * 60_000L, s.remainingMs)         // 拿满额度，而非残留的 4 分钟
    }

    @Test fun awayTimer_resetsOnReentry_midwayReturnKeepsQuota() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)                                     // 剩 4 分钟
        m.onForegroundChanged("com.tencent.mm")
        tick(29 * 60_000)                                // 脱离 29 分钟
        m.onForegroundChanged("com.ss.android.ugc.aweme")   // 中途切回：脱离计时清零
        assertEquals(4 * 60_000L, m.snapshot().remainingMs)
        m.onForegroundChanged("com.tencent.mm")          // 再次脱离
        tick(29 * 60_000)                                // 又 29 分钟：不能解除（计时已清零重来）
        assertEquals(Status.PAUSED, m.snapshot().status)
        tick(60_000)                                     // 累计满 30 分钟才解除
        assertEquals(Status.IDLE, m.snapshot().status)
    }

    @Test fun releasedByAway_noExitFired() {
        var exited = false
        m.exitListener = { exited = true }
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onForegroundChanged("com.tencent.mm")
        tick(30 * 60_000)                                // 脱离满 30 分钟触发解除
        assertFalse(exited)                              // 人已不在抖音，不该执行"返回桌面"
    }

    // ---- 分级戒断：剩余1分钟预警 + 10秒全屏警告缓冲 ----

    /** 构造可调阈值的状态机：预警线 20s、警告期 5s，方便小额度测试。 */
    private fun graded() =
        SessionManager(
            clock,
            isTargetPackage = { it == "com.ss.android.ugc.aweme" },
            awayLimitMs = 30 * 60_000L,
            warnAtMs = 20_000L,
            warningMs = 5_000L,
        )

    /** tick 重载：驱动指定状态机（graded() 构造的实例）。 */
    private fun tick(m: SessionManager, ms: Long) { clock.advance(ms); m.tick() }

    @Test fun remainingBelow1min_firesWarningOnce() {
        var warned = 0
        val m = graded()
        m.warnListener = { warned++ }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 40_000)                                  // 剩 20s：触及预警线
        assertEquals(1, warned)
        tick(m, 5_000)                                   // 继续计时不再重复预警
        tick(m, 5_000)
        assertEquals(1, warned)
    }

    @Test fun quotaExhausted_entersWarning_noImmediateExit() {
        var exited = false
        val m = graded()
        m.exitListener = { exited = true }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 60_000)                                  // 额度用完
        val s = m.snapshot()
        assertEquals(Status.WARNING, s.status)           // 先进入 10 秒警告期
        assertEquals(5_000L, s.warningRemainingMs)
        assertFalse(exited)                              // 还没回桌面（缓冲中）
    }

    @Test fun warningCountsDown_thenExits() {
        var exited = 0
        val m = graded()
        m.exitListener = { exited++ }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 60_000)                                  // 进 WARNING，剩 5s
        tick(m, 3_000)
        assertEquals(Status.WARNING, m.snapshot().status)
        assertEquals(2_000L, m.snapshot().warningRemainingMs)
        tick(m, 2_000)                                   // 警告期结束
        assertEquals(Status.IDLE, m.snapshot().status)
        assertEquals(1, exited)                          // 此刻才返回桌面
        assertEquals(0L, m.snapshot().remainingMs)
    }

    @Test fun leaveDuringWarning_countdownContinues_exitStillFires() {
        // 真机踩坑：全屏警告通知的 systemui 横幅会产生"离开目标"窗口事件，
        // 曾把 WARNING 打断成 IDLE 导致永不返回桌面。现规定：警告期任何离开事件都忽略。
        var exited = 0
        val m = graded()
        m.exitListener = { exited++ }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 60_000)                                  // WARNING 中（5s 缓冲）
        m.onForegroundChanged("com.android.systemui")    // 警告横幅/系统窗口：不算离开
        m.onForegroundChanged("com.tencent.mm")          // 真切走也忽略：缓冲期必须走完
        assertEquals(Status.WARNING, m.snapshot().status)
        tick(m, 5_000)                                   // 缓冲走完
        assertEquals(Status.IDLE, m.snapshot().status)
        assertEquals(1, exited)                          // 退出照常执行
    }

    @Test fun warningFlagsReset_forNextSession() {
        var warned = 0
        val m = graded()
        m.warnListener = { warned++ }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 40_000)                                  // 剩 20s：触发预警
        assertEquals(1, warned)
        tick(m, 25_000)                                  // 剩余 20s 用完 + 警告期 5s：第一会话结束
        assertEquals(Status.IDLE, m.snapshot().status)
        // WARNING 期结束时 inTarget 置 false；再进目标 = 新会话
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        assertEquals(Status.TIMING, m.snapshot().status)
        assertEquals(60_000L, m.snapshot().remainingMs)  // 满额度新会话
        tick(m, 40_000)                                  // 再次触及预警线
        assertEquals(2, warned)                          // 新会话预警正常再触发
    }

    // ---- 仅条数模式：有效观看（≥5秒）计数，达条数触发戒断 ----

    /** 条数模式状态机：不限时长，3 条触发。 */
    private fun counter() = SessionManager(
        clock,
        isTargetPackage = { it == "com.ss.android.ugc.aweme" },
        awayLimitMs = 30 * 60_000L,
        warnAtMs = 0L,                    // 条数模式无时长预警线
        warningMs = 5_000L,
        validVideoMs = 5_000L,
    )

    private fun swipe(m: SessionManager) = m.onVideoChanged()

    @Test fun countMode_under5s_notCounted() {
        val m = counter()
        m.configure(0L, 3)                                    // 仅条数：3 条
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 4_999)                                        // 看 4.999 秒就划走
        swipe(m)
        assertEquals(Status.TIMING, m.snapshot().status)      // 不计数、不戒断
        assertEquals(0, m.snapshot().watchedVideos)
    }

    @Test fun countMode_5sOrMore_counted() {
        val m = counter()
        m.configure(0L, 3)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 5_000)                                        // 满 5 秒
        swipe(m)
        assertEquals(1, m.snapshot().watchedVideos)
        assertEquals(Status.TIMING, m.snapshot().status)      // 未达 3 条继续
    }

    @Test fun countMode_reachMax_entersWarning_thenExits() {
        var exited = 0
        val m = counter()
        m.exitListener = { exited++ }
        m.configure(0L, 2)                                    // 2 条触发
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 6_000); swipe(m)                              // 第 1 条
        tick(m, 6_000); swipe(m)                              // 第 2 条 → 立即进警告期
        assertEquals(Status.WARNING, m.snapshot().status)
        assertEquals(2, m.snapshot().watchedVideos)
        assertFalse(exited > 0)
        tick(m, 5_000)                                        // 警告期走完
        assertEquals(Status.IDLE, m.snapshot().status)
        assertEquals(1, exited)                               // 返回桌面
    }

    @Test fun countMode_videoTimer_pausesWhileAway() {
        val m = counter()
        m.configure(0L, 1)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 3_000)                                        // 本条看了 3 秒
        m.onForegroundChanged("com.tencent.mm")               // 切走：本条视频计时也暂停
        tick(m, 60_000)
        m.onForegroundChanged("com.ss.android.ugc.aweme")     // 切回：续看同一条
        tick(m, 2_000)                                        // 补满 5 秒
        swipe(m)
        assertEquals(1, m.snapshot().watchedVideos)           // 3s+2s=5s 计 1 条
    }

    @Test fun countMode_newSession_countersReset() {
        val m = counter()
        m.configure(0L, 2)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 6_000); swipe(m)
        tick(m, 6_000); swipe(m)                              // 2 条 → WARNING
        tick(m, 5_000)                                        // 退出，IDLE
        m.onForegroundChanged("com.ss.android.ugc.aweme")     // 新会话
        assertEquals(Status.TIMING, m.snapshot().status)
        assertEquals(0, m.snapshot().watchedVideos)           // 条数清零重拿 2 条额度
    }

    @Test fun countMode_preWarns_onLastRemainingVideo() {
        // 规格：分级戒断"剩余 1 条"也要轻提示（review P1-2）
        var warned = 0
        val m = counter()
        m.warnListener = { warned++ }
        m.configure(0L, 3)                                    // 3 条额度
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 6_000); swipe(m)                              // 第 1 条：不预警
        assertEquals(0, warned)
        tick(m, 6_000); swipe(m)                              // 第 2 条：已看 2/3，剩最后 1 条 → 预警
        assertEquals(1, warned)
        tick(m, 6_000); swipe(m)                              // 第 3 条：达标戒断
        assertEquals(Status.WARNING, m.snapshot().status)
        assertEquals(1, warned)                               // 预警不重复
    }

    @Test fun countMode_swipeOutsideTarget_ignored() {
        val m = counter()
        m.configure(0L, 1)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(m, 6_000)
        m.onForegroundChanged("com.tencent.mm")               // 在微信里的滚动事件不算
        swipe(m)
        assertEquals(0, m.snapshot().watchedVideos)
    }

    // ---- 到点退出 ----

    @Test fun quotaExhausted_invokesExit_thenIdle() {
        var exited = false
        m.exitListener = { exited = true }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)                                     // 额度正好用完 → 10 秒警告期（默认）
        assertEquals(Status.WARNING, m.snapshot().status)
        assertFalse(exited)
        tick(10_000)                                     // 警告期走完
        assertTrue(exited)
        assertEquals(Status.IDLE, m.snapshot().status)
        assertEquals(0L, m.snapshot().remainingMs)
    }

    @Test fun afterExit_reenterStartsNewSession_noCooldown() {
        var exited = false
        m.exitListener = { exited = true }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(70_000)                                     // 计时 60s + 警告期 10s
        assertTrue(exited)
        m.onForegroundChanged("com.ss.android.ugc.aweme")   // 立即重开
        assertEquals(Status.TIMING, m.snapshot().status)     // 无冷却，新会话满额度
        assertEquals(60_000L, m.snapshot().remainingMs)
    }

    // ---- 额度配置 ----

    @Test fun configure_doesNotDisturbRunningSession() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)
        m.configure(30 * 60_000L)                        // 中途改预设
        assertEquals(4 * 60_000L, m.snapshot().remainingMs)  // 当前会话不受影响
    }

    // ---- 事件幂等 ----

    @Test fun repeatedEvents_noSideEffect() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onForegroundChanged("com.ss.android.ugc.aweme")   // 重复事件
        tick(1_000)
        m.onForegroundChanged("com.tencent.mm")
        m.onForegroundChanged("com.tencent.mm")             // 重复事件
        assertEquals(Status.PAUSED, m.snapshot().status)
        assertEquals(5 * 60_000L - 1_000, m.snapshot().remainingMs)
    }

    // ---- 总开关 ----

    @Test fun reset_endsSessionImmediately() {
        m.configure(5 * 60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(30_000)
        m.reset()
        assertEquals(Status.IDLE, m.snapshot().status)
        assertEquals(0L, m.snapshot().remainingMs)
    }

    // ---- 测试替身包名 ----

    @Test fun overridePredicate_treatsSubstituteAsTarget() {
        val m2 = SessionManager(clock, isTargetPackage = { it == "com.android.chrome" })
        m2.configure(60_000L)
        m2.onForegroundChanged("com.ss.android.ugc.aweme")  // 正式抖音包名不算
        assertEquals(Status.IDLE, m2.snapshot().status)
        m2.onForegroundChanged("com.android.chrome")        // 替身包名算
        assertEquals(Status.TIMING, m2.snapshot().status)
    }
}
