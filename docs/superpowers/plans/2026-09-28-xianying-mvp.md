# 限映 MVP（抖音时长模式闭环）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现限映 MVP：打开抖音弹限额面板 → 前台计时（离开暂停/重进继续）→ 剩 1 分钟预警 → 全屏警告 + 10 秒倒计时 + 震动/提示音 → 返回桌面 → 脱离 30 分钟会话解除。

**Architecture:** AccessibilityService（`TYPE_WINDOW_STATE_CHANGED`）作为唯一事件入口驱动纯 Kotlin SessionManager 状态机（注入时钟接口，可 JVM 单测）；前台服务托管 1 秒 ticker 与常驻通知；悬浮窗（SAW）承载设置面板/预警条/全屏警告；倒计时结束经 `GLOBAL_ACTION_HOME` 返回桌面。设计文档：`docs/superpowers/specs/2026-09-28-xianying-mvp-design.md`。

**Tech Stack:** Kotlin + Jetpack Compose（主界面）、JUnit4（状态机单测）、AccessibilityService、Foreground Service（specialUse）、WindowManager overlay。

**环境现状（执行前必读）：**
- 本机（Windows）**未安装 Android Studio**——Task 1 由用户完成安装与工程创建（GUI 向导只能人操作）
- 测试真机不便连线——所有真机验证 = `gradlew assembleDebug` 出 APK → 微信/QQ 传手机安装 → 按 `TESTING.md` 手测反馈
- 单元测试全部为纯 JVM（`gradlew test`），无需设备
- 本目录不是 git 仓库——Task 1 初始化

**文件结构总览（Task 2-9 创建/修改的文件）：**

```
app/src/main/java/com/xianying/app/
├── MainActivity.kt                  # 主界面入口（开关+权限引导+调试日志）
├── model/Models.kt                  # TargetApp/ControlMode/SessionConfig
├── session/Clock.kt                 # 时钟接口 + 系统实现
├── session/SessionState.kt          # Status 枚举 + SessionSnapshot 快照
├── session/SessionManager.kt        # 状态机（纯 Kotlin）
├── runtime/Runtime.kt               # 全局单例接线
├── service/MonitorAccessibilityService.kt  # 事件入口 + 事件环形日志
├── service/KeepAliveForegroundService.kt   # 前台服务 + ticker + 通知 + 震动/提示音
├── control/OverlayController.kt     # 悬浮窗：面板/预警条/全屏倒计时
├── control/ExitExecutor.kt          # 返回桌面
└── res/xml/accessibility_service_config.xml
app/src/test/java/com/xianying/app/session/SessionManagerTest.kt
TESTING.md
```

---

### Task 1: 环境准备与工程创建（需用户参与）

**Files:**
- Create: 整个 Android 工程骨架（向导生成）
- Create: `.gitignore`

- [ ] **Step 1: 用户安装 Android Studio**

用户执行（或让 Claude 代跑）：

```powershell
winget install Google.AndroidStudio
```

安装后启动一次完成首次配置向导（标准安装，接受默认 SDK）。

- [ ] **Step 2: 用户用向导创建工程**

Android Studio → New Project → **Empty Activity**（Compose 模板）：
- Name: `限映`，Package: `com.xianying.app`，Save location: `C:\Users\Administrator\Desktop\myfather\limit-video\xianying`
- Minimum SDK: **API 26**
- Build configuration language: Kotlin DSL

- [ ] **Step 3: 写 .gitignore 并初始化 git**

在 `limit-video/` 根目录创建：

```gitignore
xianying/.gradle/
xianying/build/
xianying/app/build/
xianying/local.properties/
xianying/.idea/
*.apk
```

```powershell
git init
```

- [ ] **Step 4: 验证工程可构建**

```powershell
cd xianying; .\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`（首次下载依赖可能 10 分钟以上）。

- [ ] **Step 5: Commit**

```powershell
git add .; git commit -m "chore: Android 工程骨架（AS 向导生成）"
```

---

### Task 2: model 与时钟接口（TDD）

**Files:**
- Create: `app/src/main/java/com/xianying/app/model/Models.kt`
- Create: `app/src/main/java/com/xianying/app/session/Clock.kt`
- Test: `app/src/test/java/com/xianying/app/model/ModelsTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/xianying/app/model/ModelsTest.kt`：

```kotlin
package com.xianying.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    @Test
    fun douyin_package_resolves() {
        assertEquals(TargetApp.DOUYIN, TargetApp.fromPackage("com.ss.android.ugc.aweme"))
    }

    @Test
    fun unknown_package_returns_null() {
        assertNull(TargetApp.fromPackage("com.tencent.mm"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd xianying; .\gradlew.bat test --tests "com.xianying.app.model.ModelsTest"
```

Expected: 编译失败（`TargetApp` 未定义）。

- [ ] **Step 3: 实现**

`app/src/main/java/com/xianying/app/model/Models.kt`：

```kotlin
package com.xianying.app.model

/** 受管控的短视频平台。MVP 仅抖音，后续扩展快手/B站。 */
enum class TargetApp(val packageName: String) {
    DOUYIN("com.ss.android.ugc.aweme");

    companion object {
        fun fromPackage(pkg: String?): TargetApp? =
            entries.firstOrNull { it.packageName == pkg }
    }
}

/** MVP 只有时长/无限次两种模式；条数与双重限制为后续迭代。 */
enum class ControlMode { DURATION, UNLIMITED }

data class SessionConfig(
    val mode: ControlMode,
    /** 仅 DURATION 模式有效，毫秒 */
    val durationMs: Long = 0L,
    val target: TargetApp = TargetApp.DOUYIN,
)
```

`app/src/main/java/com/xianying/app/session/Clock.kt`：

```kotlin
package com.xianying.app.session

/** 时间源接口。实现必须基于单调时钟（elapsedRealtime），不受用户改系统时间影响。 */
interface Clock { fun now(): Long }

class SystemElapsedClock : Clock {
    override fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
```

`app/src/test/java/com/xianying/app/session/FakeClock.kt`（仅测试用）：

```kotlin
package com.xianying.app.session

class FakeClock(var t: Long = 0L) : Clock {
    override fun now(): Long = t
    fun advance(ms: Long) { t += ms }
}
```

- [ ] **Step 4: 跑测试确认通过**

```powershell
cd xianying; .\gradlew.bat test --tests "com.xianying.app.model.ModelsTest"
```

Expected: PASS。

- [ ] **Step 5: Commit**

```powershell
git add .; git commit -m "feat: TargetApp/SessionConfig 模型与时钟接口"
```

---

### Task 3: SessionManager 状态机——基础迁移（TDD）

**Files:**
- Create: `app/src/main/java/com/xianying/app/session/SessionState.kt`
- Create: `app/src/main/java/com/xianying/app/session/SessionManager.kt`
- Test: `app/src/test/java/com/xianying/app/session/SessionManagerTest.kt`

- [ ] **Step 1: 定义状态与快照（无行为，供测试引用）**

`app/src/main/java/com/xianying/app/session/SessionState.kt`：

```kotlin
package com.xianying.app.session

import com.xianying.app.model.SessionConfig

enum class Status { IDLE, SETTING, TIMING, WARNING, PASS_THROUGH, PAUSED, FINAL_COUNTDOWN, ENDED }

/** 状态机对外只暴露不可变快照。 */
data class SessionSnapshot(
    val status: Status,
    val config: SessionConfig? = null,
    val remainingMs: Long = 0L,
    val awayMs: Long = 0L,
    val warningShown: Boolean = false,
    val secondsLeft: Int = 0,
)
```

- [ ] **Step 2: 写失败测试（基础迁移部分）**

`app/src/test/java/com/xianying/app/session/SessionManagerTest.kt`：

```kotlin
package com.xianying.app.session

import com.xianying.app.model.ControlMode
import com.xianying.app.model.SessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionManagerTest {
    private lateinit var clock: FakeClock
    private lateinit var m: SessionManager

    @Before fun setup() { clock = FakeClock(); m = SessionManager(clock) }

    private fun tick(ms: Long) { clock.advance(ms); m.tick() }

    // ---- 基础迁移 ----

    @Test fun enterTarget_fromIdle_showsSettingPanel() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        assertEquals(Status.SETTING, m.snapshot().status)
    }

    @Test fun panelDismissed_fallsToPassThrough() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelDismissed()
        assertEquals(Status.PASS_THROUGH, m.snapshot().status)
        assertEquals(ControlMode.UNLIMITED, m.snapshot().config?.mode)
    }

    @Test fun panelConfirmed_startsTimingWithFullQuota() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(5 * 60_000L)
        val s = m.snapshot()
        assertEquals(Status.TIMING, s.status)
        assertEquals(5 * 60_000L, s.remainingMs)
        assertFalse(s.warningShown)
    }

    @Test fun nonTargetPackage_ignored() {
        m.onForegroundChanged("com.tencent.mm")
        assertEquals(Status.IDLE, m.snapshot().status)
    }

    @Test fun leaveTarget_becomesPaused_awayAccumulates() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(5 * 60_000L)
        tick(60_000)                       // 用掉 1 分钟
        m.onForegroundChanged("com.tencent.mm")
        assertEquals(Status.PAUSED, m.snapshot().status)
        tick(10 * 60_000)                  // 脱离 10 分钟
        assertEquals(10 * 60_000L, m.snapshot().awayMs)
    }

    @Test fun returnBefore30min_resumesRemainingQuota() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(5 * 60_000L)
        tick(60_000)
        m.onForegroundChanged("com.tencent.mm")
        tick(29 * 60_000)                  // 脱离 29 分钟（<30）
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        val s = m.snapshot()
        assertEquals(Status.TIMING, s.status)
        assertEquals(4 * 60_000L, s.remainingMs)   // 剩余额度保留
        assertEquals(0L, s.awayMs)
    }

    @Test fun away30min_destroysSession_nextEnterShowsPanelAgain() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(5 * 60_000L)
        m.onForegroundChanged("com.tencent.mm")
        tick(30 * 60_000)
        assertEquals(Status.IDLE, m.snapshot().status)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        assertEquals(Status.SETTING, m.snapshot().status)   // 重新弹面板
    }

    @Test fun passThrough_alsoDestroyedAfter30minAway() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelDismissed()
        m.onForegroundChanged("com.tencent.mm")
        tick(30 * 60_000)
        assertEquals(Status.IDLE, m.snapshot().status)
    }
}
```

- [ ] **Step 3: 跑测试确认编译失败**

```powershell
cd xianying; .\gradlew.bat test --tests "com.xianying.app.session.SessionManagerTest"
```

Expected: 编译失败（`SessionManager` 未定义）。

- [ ] **Step 4: 实现 SessionManager（本 Task 只实现基础迁移，预警/倒计时分支留到 Task 4，代码一次写全但测试分批）**

`app/src/main/java/com/xianying/app/session/SessionManager.kt`：

```kotlin
package com.xianying.app.session

import com.xianying.app.model.ControlMode
import com.xianying.app.model.SessionConfig
import com.xianying.app.model.TargetApp

/**
 * 纯 Kotlin 状态机，不依赖 Android。
 * 事件入口：onForegroundChanged（Accessibility 层回调）、onPanelDismissed/onPanelConfirmed（面板 UI 回调）。
 * 时间推进：外部（前台服务）每秒调用 tick()。
 */
class SessionManager(private val clock: Clock) {

    companion object {
        const val WARN_BEFORE_MS = 60_000L          // 剩 1 分钟预警
        const val AWAY_DESTROY_MS = 30 * 60_000L    // 脱离 30 分钟会话解除
        const val COUNTDOWN_SECONDS = 10
    }

    /** 每次状态变化回调（含每秒 tick 的快照更新）。 */
    var listener: ((SessionSnapshot) -> Unit)? = null
    /** FINAL_COUNTDOWN 归零时回调一次，由 ExitExecutor 执行返回桌面。 */
    var exitListener: (() -> Unit)? = null

    private var status = Status.IDLE
    private var config: SessionConfig? = null
    private var remainingMs = 0L
    private var awayMs = 0L
    private var warningShown = false
    private var secondsLeft = 0
    private var inTarget = false
    private var lastTickMs = clock.now()

    fun snapshot() = SessionSnapshot(status, config, remainingMs, awayMs, warningShown, secondsLeft)

    /** pkg 为当前前台包名；目标 App 之外任意值（含 null）都算"离开"。 */
    fun onForegroundChanged(pkg: String?) {
        val isTarget = TargetApp.fromPackage(pkg) != null
        if (isTarget == inTarget) return
        inTarget = isTarget
        if (isTarget) onEnterTarget() else onLeaveTarget()
    }

    /** 用户关闭面板 = 无限次放行。 */
    fun onPanelDismissed() {
        if (status != Status.SETTING) return
        config = SessionConfig(ControlMode.UNLIMITED)
        status = Status.PASS_THROUGH
        emit()
    }

    fun onPanelConfirmed(durationMs: Long) {
        if (status != Status.SETTING || durationMs <= 0) return
        config = SessionConfig(ControlMode.DURATION, durationMs)
        remainingMs = durationMs
        warningShown = false
        status = Status.TIMING
        emit()
    }

    fun tick() {
        val now = clock.now()
        val dt = now - lastTickMs
        lastTickMs = now
        when (status) {
            Status.TIMING, Status.WARNING -> {
                remainingMs -= dt
                if (!warningShown && remainingMs in 1..WARN_BEFORE_MS) {
                    warningShown = true; status = Status.WARNING
                }
                if (remainingMs <= 0) {
                    remainingMs = 0
                    secondsLeft = COUNTDOWN_SECONDS
                    status = Status.FINAL_COUNTDOWN
                }
            }
            Status.PAUSED -> {
                awayMs += dt
                if (awayMs >= AWAY_DESTROY_MS) { resetToIdle() }
            }
            Status.FINAL_COUNTDOWN -> {
                val leftMs = secondsLeft * 1000L - dt
                secondsLeft = ((leftMs + 999) / 1000).toInt().coerceAtLeast(0)
                if (secondsLeft <= 0) {
                    status = Status.ENDED; emit()
                    exitListener?.invoke()
                    resetToIdle()
                    return
                }
            }
            else -> { /* IDLE/SETTING/PASS_THROUGH/ENDED 无时间迁移 */ }
        }
        emit()
    }

    private fun onEnterTarget() {
        when (status) {
            Status.IDLE -> { status = Status.SETTING; emit() }         // 弹面板
            Status.PAUSED -> {                                          // 恢复会话
                awayMs = 0
                status = if (config?.mode == ControlMode.DURATION) {
                    if (warningShown) Status.WARNING else Status.TIMING
                } else Status.PASS_THROUGH
                emit()
            }
            else -> Unit
        }
    }

    private fun onLeaveTarget() {
        when (status) {
            Status.SETTING -> { resetToIdle() }                         // 面板没选就走了 → 无会话
            Status.TIMING, Status.WARNING, Status.PASS_THROUGH -> {
                awayMs = 0
                status = Status.PAUSED; emit()
            }
            Status.FINAL_COUNTDOWN -> {                                 // 用户倒计时中自己退出
                status = Status.ENDED; emit(); resetToIdle()
            }
            else -> Unit
        }
    }

    private fun resetToIdle() {
        status = Status.IDLE; config = null
        remainingMs = 0; awayMs = 0; warningShown = false; secondsLeft = 0
        emit()
    }

    private fun emit() { listener?.invoke(snapshot()) }
}
```

- [ ] **Step 5: 跑测试确认通过**

```powershell
cd xianying; .\gradlew.bat test --tests "com.xianying.app.session.SessionManagerTest"
```

Expected: 全部 PASS。

- [ ] **Step 6: Commit**

```powershell
git add .; git commit -m "feat: SessionManager 状态机基础迁移（设置/计时/暂停/脱离解除）"
```

---

### Task 4: SessionManager 状态机——预警与倒计时（TDD）

**Files:**
- Test: `app/src/test/java/com/xianying/app/session/SessionManagerTest.kt`（追加）

- [ ] **Step 1: 追加失败测试**

在 `SessionManagerTest` 类内追加：

```kotlin
    // ---- 预警与倒计时 ----

    @Test fun remainingOneMinute_showsWarning_statusChanges() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(5 * 60_000L)
        tick(4 * 60_000L - 1)             // 剩 1 分 01 秒 → 未预警
        assertEquals(Status.TIMING, m.snapshot().status)
        tick(2_000)                       // 剩 59 秒 → 预警
        val s = m.snapshot()
        assertEquals(Status.WARNING, s.status)
        assertTrue(s.warningShown)
    }

    @Test fun quotaExhausted_entersFinalCountdown10s() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(60_000L)
        tick(60_000)
        val s = m.snapshot()
        assertEquals(Status.FINAL_COUNTDOWN, s.status)
        assertEquals(10, s.secondsLeft)
    }

    @Test fun countdownReachesZero_exitInvoked_thenIdle() {
        var exited = false
        m.exitListener = { exited = true }
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(60_000L)
        tick(60_000 + 10_000)             // 额度耗尽 + 10 秒倒计时走完
        assertTrue(exited)
        assertEquals(Status.IDLE, m.snapshot().status)
    }

    @Test fun userLeavesDuringCountdown_endsWithoutExit() {
        var exited = false
        m.exitListener = { exited = true }
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(60_000L)
        tick(60_000 + 3_000)              // 倒计时还剩 ~7 秒
        m.onForegroundChanged("com.tencent.mm")  // 用户自己退出
        assertEquals(Status.IDLE, m.snapshot().status)
        assertFalse(exited)
    }

    @Test fun pausedDuringWarning_resumesToWarning() {
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        m.onPanelConfirmed(2 * 60_000L)
        tick(61_000)                      // 已预警
        m.onForegroundChanged("com.tencent.mm")
        tick(5_000)
        m.onForegroundChanged("com.ss.android.ugc.aweme")
        assertEquals(Status.WARNING, m.snapshot().status)
    }
```

- [ ] **Step 2: 跑测试**

```powershell
cd xianying; .\gradlew.bat test --tests "com.xianying.app.session.SessionManagerTest"
```

Expected: PASS（Task 3 已实现全部逻辑；若失败按断言修复 `tick()` 分支）。

- [ ] **Step 3: Commit**

```powershell
git add .; git commit -m "test: 预警/倒计时/中途退出状态机测试全覆盖"
```

---

### Task 5: Accessibility 服务与事件日志（真机验证点 ①）

**Files:**
- Create: `app/src/main/java/com/xianying/app/service/MonitorAccessibilityService.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 无障碍服务配置**

`app/src/main/res/xml/accessibility_service_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="200" />
```

说明：不限定 `packageNames`（需接收所有 App 的窗口切换事件）；MVP 不读窗口内容节点，`canRetrieveWindowContent=false` 降低权限敏感度。

- [ ] **Step 2: 实现 MonitorAccessibilityService（含事件环形日志，替代 logcat）**

`app/src/main/java/com/xianying/app/service/MonitorAccessibilityService.kt`：

```kotlin
package com.xianying.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.xianying.app.runtime.Runtime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorAccessibilityService : AccessibilityService() {

    companion object {
        /** 最近 200 条事件，主界面调试区展示（真机不便连线，靠它排查） */
        val recentEvents = ArrayDeque<String>()

        private fun log(pkg: String?) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            recentEvents.addLast("$ts  ${pkg ?: "(null)"}")
            while (recentEvents.size > 200) recentEvents.removeFirst()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Runtime.monitorService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return          // 忽略自己
        log(pkg)
        Runtime.sessionManager.onForegroundChanged(pkg)
    }

    override fun onInterrupt() { /* 无需处理 */ }

    override fun onDestroy() {
        Runtime.monitorService = null
        super.onDestroy()
    }
}
```

- [ ] **Step 3: Runtime 单例**

`app/src/main/java/com/xianying/app/runtime/Runtime.kt`：

```kotlin
package com.xianying.app.runtime

import android.content.Context
import com.xianying.app.control.OverlayController
import com.xianying.app.service.MonitorAccessibilityService
import com.xianying.app.session.SessionManager
import com.xianying.app.session.SystemElapsedClock

/** 全局接线：Accessibility 服务、状态机、悬浮窗控制器共用同一实例。 */
object Runtime {
    val clock = SystemElapsedClock()
    val sessionManager by lazy { SessionManager(clock) }
    @Volatile var monitorService: MonitorAccessibilityService? = null
    @Volatile var overlay: OverlayController? = null
    @Volatile var appContext: Context? = null
}
```

- [ ] **Step 4: Manifest 注册与权限**

`app/src/main/AndroidManifest.xml` 的 `<manifest>` 内追加权限：

```xml
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

`<application>` 内追加：

```xml
        <service
            android:name=".service.MonitorAccessibilityService"
            android:label="限映监控"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
```

- [ ] **Step 5: 构建并真机验证（验证点 ①：能否识别抖音前台）**

```powershell
cd xianying; .\gradlew.bat assembleDebug
```

APK 路径：`app/build/outputs/apk/debug/app-debug.apk` → 传手机安装。
临时验证：MainActivity 里还没有调试日志区（Task 8 做），本步先用向导生成的 MainActivity 验证：设置 → 无障碍 → 开启"限映监控" → 打开抖音再切回本 App（此时界面还是模板），确认无崩溃、无报错。若需看事件，可跳过本步与 Task 8 一起验证。

- [ ] **Step 6: Commit**

```powershell
git add .; git commit -m "feat: Accessibility 监控服务 + 事件环形日志 + Runtime 接线"
```

---

### Task 6: 前台服务、ticker、常驻通知、震动/提示音、返回桌面（真机验证点 ②）

**Files:**
- Create: `app/src/main/java/com/xianying/app/service/KeepAliveForegroundService.kt`
- Create: `app/src/main/java/com/xianying/app/control/ExitExecutor.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: ExitExecutor**

`app/src/main/java/com/xianying/app/control/ExitExecutor.kt`：

```kotlin
package com.xianying.app.control

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.xianying.app.runtime.Runtime

/** 倒计时结束动作：提示音 + 震动 + Accessibility 返回桌面。 */
object ExitExecutor {

    fun alertAndExit(context: android.content.Context) {
        // 持续震动
        val vibrator = context.getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 500, 250)   // 等待,震动,暂停 循环
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))

        // 提示音
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                .startTone(ToneGenerator.TONE_PROP_BEEP2, 800)
        }

        // 返回桌面
        Runtime.monitorService?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        )
    }

    fun stopAlert(context: android.content.Context) {
        context.getSystemService(Vibrator::class.java)?.cancel()
    }
}
```

- [ ] **Step 2: KeepAliveForegroundService**

`app/src/main/java/com/xianying/app/service/KeepAliveForegroundService.kt`：

```kotlin
package com.xianying.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.xianying.app.control.ExitExecutor
import com.xianying.app.model.ControlMode
import com.xianying.app.runtime.Runtime
import com.xianying.app.session.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 前台服务：宿主 ticker（每秒 tick 状态机）+ 常驻通知显示剩余额度。 */
class KeepAliveForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "xianying_monitor"
        private const val NOTI_ID = 1
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        val noti = buildNotification("限映监控运行中")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }

        // 状态机 → 通知文案 + 倒计时结束动作
        Runtime.sessionManager.listener = { snap ->
            when (snap.status) {
                Status.FINAL_COUNTDOWN, Status.ENDED ->
                    if (snap.status == Status.FINAL_COUNTDOWN && snap.secondsLeft == 10)
                        ExitExecutor.stopAlert(this)  // 先停上一次，避免叠加（真正触发在归零）
                else -> Unit
            }
            updateNotification(notificationText(snap))
        }
        Runtime.sessionManager.exitListener = { ExitExecutor.alertAndExit(this) }

        // ticker
        scope.launch {
            while (isActive) {
                Runtime.sessionManager.tick()
                delay(1_000)
            }
        }
    }

    private fun notificationText(snap: com.xianying.app.session.SessionSnapshot): String = when (snap.status) {
        Status.IDLE -> "限映监控运行中"
        Status.SETTING -> "等待设置本次额度…"
        Status.TIMING, Status.WARNING ->
            if (snap.config?.mode == ControlMode.DURATION)
                "剩余 ${snap.remainingMs / 60_000} 分 ${snap.remainingMs % 60_000 / 1000} 秒"
            else "无限次模式"
        Status.PASS_THROUGH -> "无限次模式"
        Status.PAUSED -> "已暂停（脱离计时中）"
        Status.FINAL_COUNTDOWN -> "额度已用完！${snap.secondsLeft} 秒后结束"
        Status.ENDED -> "本次使用结束"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "限映监控", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("限映")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        ExitExecutor.stopAlert(this)
        Runtime.sessionManager.listener = null
        Runtime.sessionManager.exitListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

- [ ] **Step 3: Manifest 注册前台服务**

`<application>` 内追加（与 Task 5 的无障碍服务并列）：

```xml
        <service
            android:name=".service.KeepAliveForegroundService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="short_video_session_monitor" />
        </service>
```

- [ ] **Step 4: 构建 + 真机验证（验证点 ②：前台服务存活与通知）**

```powershell
cd xianying; .\gradlew.bat assembleDebug
```

装到手机：本 Task 尚无 UI 开关（Task 8 做），临时验证可用 `adb` 不便则直接与 Task 8 合并验证；若手机可短暂连一次电脑，`adb shell am start-foreground-service` 非必需——**跳过，统一在 Task 8 验证**。

- [ ] **Step 5: Commit**

```powershell
git add .; git commit -m "feat: 前台服务 + ticker + 常驻通知 + 倒计时结束动作"
```

---

### Task 7: 悬浮窗三件套——设置面板/预警条/全屏倒计时（真机验证点 ③④）

**Files:**
- Create: `app/src/main/java/com/xianying/app/control/OverlayController.kt`
- Modify: `app/src/main/java/com/xianying/app/service/KeepAliveForegroundService.kt`

- [ ] **Step 1: OverlayController（纯 View 实现，避免 Compose 悬浮窗生命周期问题）**

`app/src/main/java/com/xianying/app/control/OverlayController.kt`：

```kotlin
package com.xianying.app.control

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xianying.app.runtime.Runtime
import com.xianying.app.session.SessionSnapshot
import com.xianying.app.session.Status

/** 悬浮窗控制器：设置面板 / 顶部预警条 / 全屏倒计时。全部 TYPE_APPLICATION_OVERLAY。 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var panelView: LinearLayout? = null
    private var warnAdded = false
    private var countdownView: TextView? = null
    private var countdownAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    /** 由 KeepAliveForegroundService 的状态监听驱动。 */
    fun onStateChanged(snap: SessionSnapshot) {
        if (snap.status == Status.SETTING) showPanel()
        else hidePanel()
        if (snap.status == Status.WARNING) showWarnBar()
        if (snap.status == Status.FINAL_COUNTDOWN) showCountdown(snap.secondsLeft)
        else hideCountdown()
    }

    // ---------- 设置面板 ----------

    private fun showPanel() {
        if (panelView != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val title = TextView(context).apply {
            text = "本次抖音使用限额"
            textSize = 20f; setTextColor(Color.BLACK)
        }
        val input = EditText(context).apply {
            hint = "输入分钟数（如 15）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.BLACK)
        }
        val ok = Button(context).apply { text = "开始计时" }
        val pass = Button(context).apply { text = "不限制（无限次）" }
        ok.setOnClickListener {
            val minutes = input.text.toString().toLongOrNull()
            if (minutes != null && minutes > 0) {
                Runtime.sessionManager.onPanelConfirmed(minutes * 60_000)
            }
        }
        pass.setOnClickListener { Runtime.sessionManager.onPanelDismissed() }
        root.addView(title)
        root.addView(input)
        root.addView(ok)
        root.addView(pass)
        wm.addView(root, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER })
        panelView = root
    }

    private fun hidePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    // ---------- 预警条 ----------

    private fun showWarnBar() {
        if (warnAdded) return
        warnAdded = true
        val bar = TextView(context).apply {
            text = "⏳ 限映：剩余额度不足 1 分钟"
            textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(0xCCFF9800.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        wm.addView(bar, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP })
        bar.postDelayed({ runCatching { wm.removeView(bar) }; warnAdded = false }, 5_000)
    }

    // ---------- 全屏倒计时 ----------

    private fun showCountdown(secondsLeft: Int) {
        val tv = countdownView ?: TextView(context).apply {
            textSize = 96f; setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt()); gravity = Gravity.CENTER
            countdownView = this
        }
        tv.text = "额度已用完\n${secondsLeft} 秒后结束本次使用"
        if (!countdownAdded) {
            wm.addView(tv, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ))
            countdownAdded = true
        }
    }

    private fun hideCountdown() {
        if (countdownAdded) {
            countdownView?.let { runCatching { wm.removeView(it) } }
            countdownAdded = false
        }
    }

    fun hideAll() {
        hidePanel(); hideCountdown()
    }
}
```

- [ ] **Step 2: 前台服务接线 OverlayController**

修改 `KeepAliveForegroundService.onCreate()`：在 `Runtime.sessionManager.listener = ...` 之前插入：

```kotlin
        Runtime.overlay = OverlayController(this)
```

listener 回调开头插入：

```kotlin
            Runtime.overlay?.onStateChanged(snap)
```

同时把 Task 6 listener 中那段冗余的 `Status.FINAL_COUNTDOWN` 判断删除（`stopAlert` 的正确时机是 `Status.ENDED` 到来时），最终 listener 为：

```kotlin
        Runtime.sessionManager.listener = { snap ->
            Runtime.overlay?.onStateChanged(snap)
            if (snap.status == Status.ENDED) ExitExecutor.stopAlert(this)
            updateNotification(notificationText(snap))
        }
```

- [ ] **Step 3: 构建并真机验证（验证点 ③④：完整闭环）**

```powershell
cd xianying; .\gradlew.bat assembleDebug
```

APK 传手机。暂时没有主界面开关（Task 8），本步可与 Task 8 合并验证；若想提前验证：无障碍权限 + 悬浮窗权限都需在系统设置里手动开启（"限映"→显示在其他应用上层）。

- [ ] **Step 4: Commit**

```powershell
git add .; git commit -m "feat: 悬浮窗设置面板/预警条/全屏倒计时 + 服务接线"
```

---

### Task 8: 主界面——总开关、权限引导、调试日志（真机验证点 ⑤：全流程）

**Files:**
- Modify: `app/src/main/java/com/xianying/app/MainActivity.kt`（整体替换向导生成内容）

- [ ] **Step 1: 重写 MainActivity**

```kotlin
package com.xianying.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.view.accessibility.AccessibilityManager
import com.xianying.app.runtime.Runtime
import com.xianying.app.service.KeepAliveForegroundService
import com.xianying.app.service.MonitorAccessibilityService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Runtime.appContext = applicationContext
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MainScreen() } } }
    }

    override fun onResume() { super.onResume(); refreshTick.value++ }   // 触发权限状态刷新
    companion object { val refreshTick = mutableStateOf(0) }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val sp = ctx.getSharedPreferences("xianying", Context.MODE_PRIVATE)
    val masterOn = remember { mutableStateOf(sp.getBoolean("master", false)) }
    // onResume 时重算权限状态
    val a11yOn = accessibilityEnabled(ctx)
    val overlayOn = Settings.canDrawOverlays(ctx)
    val batteryOk = ctx.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(ctx.packageName)
    @Suppress("UNUSED_EXPRESSION") refreshTick.value  // 依赖读取以触发重组

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("限映", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.weight(1f))
            Text("总开关")
            Switch(checked = masterOn.value, onCheckedChange = { on ->
                masterOn.value = on
                sp.edit().putBoolean("master", on).apply()
                if (on) KeepAliveForegroundService.start(ctx)
                else { KeepAliveForegroundService.stop(ctx); Runtime.sessionManager.onForegroundChanged(null) }
            })
        }

        Spacer(Modifier.height(16.dp))
        PermissionRow("① 无障碍服务（必须）", a11yOn) { openAccessibilitySettings(ctx) }
        PermissionRow("② 悬浮窗（必须）", overlayOn) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")))
        }
        PermissionRow("③ 电池优化白名单（建议）", batteryOk) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        Spacer(Modifier.height(16.dp))
        Text("事件日志（最近 30 条）", style = MaterialTheme.typography.titleMedium)
        Text(MonitorAccessibilityService.recentEvents.toList().takeLast(30)
            .joinToString("\n") { if (it.length > 40) it.take(40) else it },
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✅" else "❌", Modifier.padding(end = 8.dp))
        Text(label, Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("去开启") }
    }
}

private fun accessibilityEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(AccessibilityManager::class.java)
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
}

private fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
```

- [ ] **Step 2: 构建出 APK**

```powershell
cd xianying; .\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 3: 真机验证（验证点 ⑤：MVP 完整闭环，按 TESTING.md 执行）**

见 Task 9 的 TESTING.md（先执行 Task 9 Step 1 写好清单再测，或本步直接按其内容测）。

- [ ] **Step 4: Commit**

```powershell
git add .; git commit -m "feat: 主界面总开关 + 三项权限引导 + 事件日志"
```

---

### Task 9: 手测清单与收尾

**Files:**
- Create: `TESTING.md`

- [ ] **Step 1: 写 TESTING.md**

```markdown
# 限映 MVP 真机手测清单

版本：build #（填构建日期）
安装：APK 传手机 → 安装 → 打开限映

## A. 权限与开关
- [ ] 主界面三张权限卡：①无障碍 ②悬浮窗 显示 ✅，③电池优化按提示设置
- [ ] 总开关开启后，通知栏出现"限映"常驻通知
- [ ] 总开关关闭后，通知消失；打开抖音不再弹任何东西

## B. 设置面板
- [ ] 打开抖音 → 数秒内弹出"本次抖音使用限额"面板
- [ ] 输入 2 → 点"开始计时" → 面板消失，通知显示"剩余 1 分 59 秒"左右
- [ ] 杀掉抖音重开（1 分钟内）→ 不再弹面板，通知继续倒计时（沿用剩余额度）

## C. 计时暂停恢复
- [ ] 切到微信 30 秒 → 通知变为"已暂停"
- [ ] 切回抖音 → 恢复计时，额度没多扣
- [ ] 锁屏再解锁回抖音 → 计时正常

## D. 预警与戒断
- [ ] 剩余不足 1 分钟时，屏幕顶部出现橙色预警条，约 5 秒后消失
- [ ] 额度用完 → 全屏黑色倒计时（10→1），伴随震动和提示音
- [ ] 倒计时归零 → 自动回到桌面
- [ ] 立即再开抖音 → 重新弹面板（无冷却）
- [ ] 倒计时进行中自己退出抖音 → 倒计时中断、不执行返回桌面、回到桌面后无事发生

## E. 会话自动解除
- [ ] 设 5 分钟 → 切出抖音等 30 分钟（或改小测试：见下方调试开关）
- [ ] 30 分钟后再进抖音 → 重新弹面板（旧会话已销毁）

## F. 故障处理
- [ ] 系统设置里关闭无障碍 → 常驻通知仍在但打开抖音无反应（fail-open，不干扰使用）
- [ ] 重启手机 → 打开限映一次激活服务后功能恢复

## 调试手段
- 主界面底部"事件日志"实时记录窗口切换事件，排查识别问题时看这里
```

- [ ] **Step 2: 对照设计文档终检**

对照 `docs/superpowers/specs/2026-09-28-xianying-mvp-design.md` 第 5 节状态机逐条核对 `SessionManagerTest` 覆盖；核对第 8 节错误处理表——**通知"监控已失效"的修复提醒（Accessibility 断连检测）若未实现，在主界面权限卡（无障碍 ✅/❌）已覆盖其功能，记录为已知简化**。

- [ ] **Step 3: 最终提交**

```powershell
git add .; git commit -m "docs: 真机手测清单 TESTING.md"
```

---

## 已知简化（记录，不阻塞 MVP）

1. ** Accessibility 断连检测**：未做主动断连通知，靠主界面权限卡状态代替（打开 App 即见 ❌）
2. **预警条降级**（heads-up 通知）未实现——悬浮窗权限被关时预警/全屏警告不展示，仅常驻通知有文案
3. **用户改设置时间**：`elapsedRealtime` 单调时钟已免疫
4. **抖音分屏/多窗口**：不处理
