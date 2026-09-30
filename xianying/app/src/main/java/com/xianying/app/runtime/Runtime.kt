package com.xianying.app.runtime

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xianying.app.model.ControlMode
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

    /**
     * 已启用的监控目标集合（主界面开关控制，持久化在 SharedPreferences）。
     * 用 Compose 状态持有：主界面开关一点，读它的 UI 立即重绘（曾因普通变量
     * 不触发重组，出现"点了开关界面不变"——实际已生效，纯显示问题）。
     */
    var enabledTargets: Set<TargetApp> by mutableStateOf(setOf(TargetApp.DOUYIN))

    /** 前台包名 ∈ 启用目标 → 是监控对象。 */
    fun isTarget(pkg: String?): Boolean {
        val t = TargetApp.fromPackage(pkg) ?: return false
        return t in enabledTargets
    }

    /** 最近一次处于前台的目标包名（息屏前在刷哪个 App），解锁恢复时回放用。 */
    @Volatile var lastTargetPkg: String? = null

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
        // 模式决定哪一种额度生效：仅时长=分钟数，仅条数=条数，无限次=都不限
        val mode = try {
            ControlMode.valueOf(sp.getString("mode", ControlMode.DURATION.name)!!)
        } catch (e: IllegalArgumentException) {
            ControlMode.DURATION                      // 旧版本存根兜底
        }
        sessionManager.configure(
            if (mode == ControlMode.DURATION) sp.getInt("minutes", 10) * 60_000L else 0L,
            if (mode == ControlMode.COUNT) sp.getInt("videos", 10) else 0,
        )
        return sp.getBoolean("master", false)
    }
}
