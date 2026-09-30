package com.xianying.app.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧差检测器测试：合成亮度网格序列模拟
 * ① 静止画面（暂停）② 同视频播放（局部小变化）③ 滑动切视频（整屏大变化）
 */
class FrameDiffDetectorTest {

    private val w = 16
    private val h = 28

    /**
     * 生成一帧：确定性梯度纹理。seed 差 ≥1 = 完全不同画面（每格差 = 67*Δ mod 256，
     * Δ≥1 时 ≥67 > 阈值）；mutateRatio = 前 N 格加 delta（模拟同视频局部动画）。
     */
    private fun frame(seed: Int, mutateRatio: Double = 0.0, delta: Int = 80): IntArray {
        val f = IntArray(w * h)
        val shift = (seed * 67) % 256
        for (i in f.indices) f[i] = (i * 7 + shift) % 256
        if (mutateRatio > 0) {
            val n = (f.size * mutateRatio).toInt()
            for (i in 0 until n) f[i] = (f[i] + delta + 40) % 256
        }
        return f
    }

    @Test fun staticFrames_noSwitch() {
        val d = FrameDiffDetector(w, h)
        val f0 = frame(1)
        assertFalse(d.feed(f0))                 // 首帧只建基线
        assertFalse(d.feed(frame(1)))           // 完全相同
        assertFalse(d.feed(frame(1)))
    }

    @Test fun localAnimation_noSwitch() {
        // 同一视频播放中：字幕/按钮/心形动画只改 30% 画面
        val d = FrameDiffDetector(w, h)
        d.feed(frame(7))
        assertFalse(d.feed(frame(7, mutateRatio = 0.30)))
        assertFalse(d.feed(frame(7, mutateRatio = 0.35)))
        assertFalse(d.feed(frame(7, mutateRatio = 0.28)))
    }

    @Test fun fullScreenChange_switchDetected() {
        // 滑到新视频：整屏内容换掉
        val d = FrameDiffDetector(w, h)
        d.feed(frame(100))
        assertTrue(d.feed(frame(200)))
    }

    @Test fun swipeSequence_debounce_singleSwitch() {
        // 一次滑动产生 2-3 帧剧烈过渡（滑动中间态），去抖后只算一次切换
        val d = FrameDiffDetector(w, h)
        d.feed(frame(300))
        var switches = 0
        if (d.feed(frame(301))) switches++
        if (d.feed(frame(400))) switches++      // 新视频
        if (d.feed(frame(400, mutateRatio = 0.2))) switches++
        assertEquals(1, switches)
    }

    @Test fun consecutiveVideos_eachSwitchCounted() {
        // 连刷三条：视频1→2→3，中间隔若干播放帧
        val d = FrameDiffDetector(w, h)
        d.feed(frame(10))
        var switches = 0
        if (d.feed(frame(20))) switches++                       // 切到视频2
        if (d.feed(frame(20, mutateRatio = 0.25))) switches++   // 视频2播放中
        if (d.feed(frame(30))) switches++                       // 切到视频3
        if (d.feed(frame(30, mutateRatio = 0.3))) switches++    // 视频3播放中
        assertEquals(2, switches)
    }

    @Test fun reset_clearsBaseline_noFalseSwitchOnReturn() {
        // 在抖音看视频 → 切到微信（画面全变但 gating 拦住并 reset）→ 回抖音
        // 回来的首帧只建基线，不得误报一次"切视频"
        val d = FrameDiffDetector(w, h)
        d.feed(frame(10))
        d.feed(frame(20, mutateRatio = 0.25))          // 抖音播放中
        d.reset()                                       // 离开目标场景
        assertFalse(d.feed(frame(500)))                 // 回到抖音：首帧建基线
        assertFalse(d.feed(frame(500, mutateRatio = 0.3)))  // 正常播放也不报
        assertTrue(d.feed(frame(600)))                  // 真切下一条才报
    }

    @Test fun threshold_ratios_tunable() {
        // 高阈值（95% 格子变化才算切）下 80% 变化不算；默认 70% 下算
        val strict = FrameDiffDetector(w, h, changedRatioThreshold = 0.95)
        strict.feed(frame(5))
        assertFalse(strict.feed(frame(5, mutateRatio = 0.80, delta = 120)))
        val normal = FrameDiffDetector(w, h)
        normal.feed(frame(5))
        assertTrue(normal.feed(frame(5, mutateRatio = 0.80, delta = 120)))
    }
}
