package com.xianying.app.runtime

import android.content.Context
import com.xianying.app.model.TargetApp
import com.xianying.app.service.MonitorAccessibilityService
import com.xianying.app.session.SessionManager
import com.xianying.app.session.SystemElapsedClock

/**
 * 全局接线板：无障碍服务、状态机、主界面共用同一批实例。
 * （无障碍服务与前台服务由系统分别创建，必须有个单例场所让它们找到彼此）
 */
object Runtime {

    val clock = SystemElapsedClock()

    /** 状态机唯一实例；目标判定走 isTarget（按主界面启用的目标集合）。 */
    val sessionManager: SessionManager by lazy { SessionManager(clock, ::isTarget) }

    /** 无障碍服务实例；系统连接后置入，断开置空。 */
    @Volatile var monitorService: MonitorAccessibilityService? = null

    /** App 全局 Context；MainActivity 创建时置入。 */
    @Volatile var appContext: Context? = null

    /** 已启用的监控目标集合（主界面开关控制，持久化在 SharedPreferences）。 */
    @Volatile var enabledTargets: Set<TargetApp> = setOf(TargetApp.DOUYIN)

    /** 前台包名 ∈ 启用目标 → 是监控对象。 */
    fun isTarget(pkg: String?): Boolean {
        val t = TargetApp.fromPackage(pkg) ?: return false
        return t in enabledTargets
    }
}
