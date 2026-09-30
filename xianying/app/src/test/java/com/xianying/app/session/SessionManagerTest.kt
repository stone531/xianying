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
        tick(29 * 60_000)                                // 离开任意久（MVP 不做 30 分钟解除）
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        val s = m.snapshot()
        assertEquals(Status.TIMING, s.status)
        assertEquals(4 * 60_000L, s.remainingMs)         // 剩余额度保留
    }

    // ---- 到点退出 ----

    @Test fun quotaExhausted_invokesExit_thenIdle() {
        var exited = false
        m.exitListener = { exited = true }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)                                     // 额度正好用完
        assertTrue(exited)
        assertEquals(Status.IDLE, m.snapshot().status)
        assertEquals(0L, m.snapshot().remainingMs)
    }

    @Test fun afterExit_reenterStartsNewSession_noCooldown() {
        var exited = false
        m.exitListener = { exited = true }
        m.configure(60_000L)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        tick(60_000)
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
        val m2 = SessionManager(clock) { it == "com.android.chrome" }
        m2.configure(60_000L)
        m2.onForegroundChanged("com.ss.android.ugc.aweme")  // 正式抖音包名不算
        assertEquals(Status.IDLE, m2.snapshot().status)
        m2.onForegroundChanged("com.android.chrome")        // 替身包名算
        assertEquals(Status.TIMING, m2.snapshot().status)
    }
}
