package com.xianying.app.model

/**
 * 受管控的短视频平台（写死三个，主界面只做开关，不输入包名）。
 * 包名是 App 在 Android 系统里的唯一身份证——"识别某个平台"就是比对这张表。
 * 每个平台包含其全部发行版本（正式版/极速版/火山版等），任一版本在前台都算该平台。
 *
 * 注意：抖音正式版包名已在模拟器用官方 APK 验证；其余包名为公开常识值，真机验证前
 * 不算已实现（识别失败最多"不生效"，无其他影响）。
 */
enum class TargetApp(val packages: Set<String>, val label: String, val emoji: String) {
    DOUYIN(
        setOf(
            "com.ss.android.ugc.aweme",       // 抖音正式版（已验证）
            "com.ss.android.ugc.aweme.lite",  // 抖音极速版
            "com.ss.android.ugc.live",        // 抖音火山版
        ),
        "抖音", "🎵",
    ),
    KUAISHOU(
        setOf(
            "com.smile.gifmaker",             // 快手正式版
            "com.kuaishou.nebula",            // 快手极速版
        ),
        "快手", "⚡",
    ),
    BILIBILI(
        setOf(
            "tv.danmaku.bili",                // B站正式版
            "com.bstar.intl",                 // B站国际版
        ),
        "B站", "📺",
    );

    companion object {
        /** 按包名查平台；查不到（如微信、桌面）返回 null，表示"不是目标"。 */
        fun fromPackage(pkg: String?): TargetApp? =
            entries.firstOrNull { pkg in it.packages }
    }
}

/**
 * 管控模式（规格四种，MVP 实现前三种；双重限制为后续迭代）：
 * DURATION  仅时长：目标 App 前台累计时长达 quotaMs 触发戒断
 * COUNT     仅条数：有效观看（≥5秒）视频数达 maxVideos 触发戒断
 * UNLIMITED 无限次：本次会话不管控
 */
enum class ControlMode { DURATION, COUNT, UNLIMITED }

/**
 * 本次会话额度。quotaMs<=0 表示不限时长；maxVideos<=0 表示不限条数；
 * 两者都 <=0（未设限）不建立会话。
 */
data class SessionConfig(val quotaMs: Long, val maxVideos: Int = 0)
