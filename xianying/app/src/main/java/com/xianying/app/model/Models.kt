package com.xianying.app.model

/**
 * 受管控的短视频平台（写死三个，主界面只做开关，不输入包名）。
 * 包名是 App 在 Android 系统里的唯一身份证——"识别某个平台"就是比对这张表。
 *
 * 注意：抖音包名已在模拟器用官方 APK 验证；快手/小红书包名为公开常识值，真机验证前
 * 不算已实现（识别失败最多"不生效"，无其他影响）。
 */
enum class TargetApp(val packageName: String, val label: String, val emoji: String) {
    DOUYIN("com.ss.android.ugc.aweme", "抖音", "🎵"),
    KUAISHOU("com.smile.gifmaker", "快手", "⚡"),
    XIAOHONGSHU("com.xingin.xhs", "小红书", "📕");

    companion object {
        /** 按包名查平台；查不到（如微信、桌面）返回 null，表示"不是目标"。 */
        fun fromPackage(pkg: String?): TargetApp? =
            entries.firstOrNull { it.packageName == pkg }
    }
}

/** 本次会话额度（毫秒）。quotaMs <= 0 视为未设限，不建立会话。 */
data class SessionConfig(val quotaMs: Long)
