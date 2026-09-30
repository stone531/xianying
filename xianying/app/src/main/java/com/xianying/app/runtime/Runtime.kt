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

    /** 状态机唯一实例；目标判定走 isTarget（支持测试替身包名）。 */
    val sessionManager: SessionManager by lazy { SessionManager(clock, ::isTarget) }

    /** 无障碍服务实例；系统连接后置入，断开置空。 */
    @Volatile var monitorService: MonitorAccessibilityService? = null

    /** App 全局 Context；MainActivity 创建时置入。 */
    @Volatile var appContext: Context? = null

    /**
     * 测试替身包名（模拟器上用 Chrome 等冒充抖音跑闭环）。
     * null/空 = 正式模式（抖音包名表）。由主界面"调试区"设置。
     */
    @Volatile var targetOverride: String? = null

    fun isTarget(pkg: String?): Boolean {
        val o = targetOverride
        return if (o.isNullOrBlank()) TargetApp.fromPackage(pkg) != null
        else pkg == o
    }
}
