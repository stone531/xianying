package com.xianying.app.model

/**
 * 受管控的短视频平台。MVP 仅抖音，后续扩展快手/B站。
 * 包名是 App 在 Android 系统里的唯一身份证——"识别抖音"就是比对这张表。
 */
enum class TargetApp(val packageName: String) {
    DOUYIN("com.ss.android.ugc.aweme");

    companion object {
        /** 按包名查平台；查不到（如微信、桌面）返回 null，表示"不是目标"。 */
        fun fromPackage(pkg: String?): TargetApp? =
            entries.firstOrNull { it.packageName == pkg }
    }
}

/** 本次会话额度（毫秒）。quotaMs <= 0 视为未设限，不建立会话。 */
data class SessionConfig(
    val quotaMs: Long,
    val target: TargetApp = TargetApp.DOUYIN,
)
