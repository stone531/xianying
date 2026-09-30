package com.xianying.app.detector

/**
 * 帧差视频切换检测器（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 背景：抖音信息流不发 TYPE_VIEW_SCROLLED，播放动画又造成持续 CONTENT_CHANGED
 * 火龙——无障碍事件层面对"切了一条视频"无解（2026-09-30 全事件侦察实测）。
 * 本检测器走设计文档的备选路线：录屏采样 + 相邻帧比对。
 *
 * 判定规则（确定性阈值，不用模糊规则）：
 *  - 把每帧降采样为 w×h 的亮度网格，与上一帧逐格差分
 *  - 变化幅度超过 cellThreshold 且比例超过 changedRatioThreshold → "整屏大变化"
 *    （同一视频播放：字幕/动画只改局部 ~30%；切视频：整屏内容替换 >90%）
 *  - 一次切换 = 大变化帧，且前一帧不是大变化（连续大变化帧 = 同一次滑动的过渡中间态，
 *    链式抑制不重复计数）；再加 minGapMs 硬间隔防动画闪烁误触
 *
 * 参数经构造注入，便于真机调参；默认值是模拟器初调结果。
 */
class FrameDiffDetector(
    private val w: Int,
    private val h: Int,
    /** 单格亮度变化超过此值才算"该格变了"（0~255）。 */
    private val cellThreshold: Int = 30,
    /** "变了"的格子占比超过此值才算整屏切换（0.0~1.0）。 */
    private val changedRatioThreshold: Double = 0.70,
    /** 两次切换的最小间隔（毫秒），防动画闪烁。 */
    private val minGapMs: Long = 600,
) {
    private var prev: IntArray? = null
    private var prevWasBig = false            // 上一帧是否为整屏大变化（过渡链判定）
    private var lastSwitchAt = Long.MIN_VALUE / 2   // 负无穷：首次真实切换不被间隔误吞
    private var nowMs = 0L                    // 由外部 feed 频率隐式推进（每帧 +采样间隔）

    /** 采样间隔（毫秒），构造后由采样端设置，用于间隔计时。 */
    var sampleIntervalMs: Long = 400

    /**
     * 喂入一帧（长度必须 = w*h，每格为 0~255 亮度）。
     * 返回 true = 检测到一次"切换到新视频"。
     */
    fun feed(luma: IntArray): Boolean {
        require(luma.size == w * h) { "frame size ${luma.size} != $w*$h" }
        val p = prev
        prev = luma.copyOf()
        nowMs += sampleIntervalMs
        if (p == null) { prevWasBig = false; return false }   // 首帧只建基线
        var changed = 0
        for (i in luma.indices) {
            if (kotlin.math.abs(luma[i] - p[i]) > cellThreshold) changed++
        }
        val big = changed.toDouble() / luma.size >= changedRatioThreshold
        val isSwitch = big && !prevWasBig && (nowMs - lastSwitchAt >= minGapMs)
        if (isSwitch) lastSwitchAt = nowMs
        prevWasBig = big
        return isSwitch
    }
}
