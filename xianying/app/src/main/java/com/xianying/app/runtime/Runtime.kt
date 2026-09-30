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

    /**
     * 从本地设置恢复运行状态（幂等，可重复调用）。
     * 两个调用时机：① 主界面启动 ② 无障碍服务被系统重新绑定（进程被杀后自愈）。
     * 返回总开关状态，调用方据此决定是否拉起前台服务。
     */
    fun restoreFromPrefs(context: Context): Boolean {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences("xianying", Context.MODE_PRIVATE)
        enabledTargets = TargetApp.entries.filter {
            sp.getBoolean("target_${it.name}", it == TargetApp.DOUYIN)
        }.toSet()
        sessionManager.configure(sp.getInt("minutes", 10) * 60_000L)
        return sp.getBoolean("master", false)
    }
}
