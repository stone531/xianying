package com.xianying.app.session

import com.xianying.app.model.SessionConfig

/**
 * 会话状态（降级版 MVP，仅三个状态）：
 * IDLE   无会话（未设额度 / 额度用完已退出 / 总开关关闭）
 * TIMING 目标 App 在前台，额度倒计时中
 * PAUSED 用户离开目标 App，额度冻结保留（重进继续，不重置）
 */
enum class Status { IDLE, TIMING, PAUSED }

/** 状态机对外只暴露不可变快照，UI/服务层照快照展示，不碰内部字段。 */
data class SessionSnapshot(
    val status: Status,
    val config: SessionConfig? = null,
    /** 剩余额度（毫秒）。非 TIMING/PAUSED 时为 0。 */
    val remainingMs: Long = 0L,
)
