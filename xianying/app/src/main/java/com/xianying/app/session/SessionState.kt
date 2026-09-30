package com.xianying.app.session

import com.xianying.app.model.SessionConfig

/**
 * 会话状态：
 * IDLE    无会话（未设额度 / 会话已终结 / 总开关关闭）
 * TIMING  目标 App 在前台，额度倒计时中
 * PAUSED  用户离开目标 App，额度冻结保留（重进继续；脱离满 30 分钟自动解除）
 * WARNING 额度已用完，分级戒断缓冲期（全屏警告 + 10 秒倒计时），结束后返回桌面
 */
enum class Status { IDLE, TIMING, PAUSED, WARNING }

/** 状态机对外只暴露不可变快照，UI/服务层照快照展示，不碰内部字段。 */
data class SessionSnapshot(
    val status: Status,
    val config: SessionConfig? = null,
    /** 剩余时长额度（毫秒）。非 TIMING/PAUSED 或不限时长时为 0。 */
    val remainingMs: Long = 0L,
    /** WARNING 警告期剩余毫秒；非 WARNING 时为 0。 */
    val warningRemainingMs: Long = 0L,
    /** 本会话已计有效观看条数。IDLE 时为 0。 */
    val watchedVideos: Int = 0,
)
