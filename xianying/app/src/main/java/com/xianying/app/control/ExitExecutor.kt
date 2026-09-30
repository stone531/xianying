package com.xianying.app.control

import android.accessibilityservice.AccessibilityService
import com.xianying.app.runtime.Runtime

/**
 * 返回桌面执行器——额度到点的唯一动作（设计文档 0.5 节：直接送回桌面）。
 * 原理：让无障碍服务替用户"按一下 Home 键"。
 * 这是系统提供的正规能力，不涉及关闭/杀死抖音。
 */
object ExitExecutor {

    /** 返回 true 表示动作已发出（无障碍服务在线）；false = 服务掉线，主界面可据此提示。 */
    fun exitToHome(): Boolean {
        val svc = Runtime.monitorService ?: return false
        return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }
}
