# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **设计文档**：`docs/superpowers/specs/2026-09-28-xianying-mvp-design.md`（MVP 设计，已定稿）
> **实现计划**：`docs/superpowers/plans/2026-09-28-xianying-mvp.md`（Task 1~9，含完整代码与测试，按 Task 逐个执行，开发前必读）

本文件是面向开发的项目要点与工作规则。

## 项目概述

**限映（XianYing）**：基于 Android 原生能力的本地短视频使用行为监测与自主管控工具。

核心理念：**由使用者自己决定本次短视频使用额度，通过单次 Session（会话）+ 分级柔性戒断进行自主管控，帮助建立自控习惯，而非粗暴限制。**

当前状态：**降级版 MVP 已开发完成，模拟器闭环验证通过（2026-09-30）。** 下一步：真机终验（MacroDroid 零代码验证 + APK 装手机跑抖音本体）。首期只做抖音，仅时长模式闭环。

**2026-09-30 修订要点（老师评审，优先级最高）**：
- 硬约束 = 不懂代码 + AI 写码 → 只走确定性路径（包名二元判断），真机调试次数压到接近零
- **先零代码验证**：用 MacroDroid/Tasker 类成熟工具配「打开抖音计时，N 分钟后按 Home」规则，验证 ①能否检测抖音前台 ②能否自动回桌面。工具够用甚至可以不开发 App
- **MVP 边界降级为**：仅抖音 + 仅时长 + 前台计时 + 到点返回桌面。悬浮窗设置面板（改为 App 内预设时长）、预警条、全屏倒计时/震动/提示音、30 分钟会话解除全部移出 MVP，降为后续增强
- 实现计划中受影响的 Task（悬浮窗三件套、预警/倒计时）需按修订调整后再执行

Android 工程由用户经 Android Studio 向导创建于 `xianying/` 子目录（包名 `com.xianying.app`，minSdk 26，Kotlin DSL）；本目录尚未初始化 git（实现计划 Task 1 一并完成）。

## 产品红线（不可违背）

- 不做家长监控、不做云端统计、不保存历史使用记录、不做广告、不做社交、不做强制每日配额
- 不需要服务器、用户账号、数据库；所有数据仅存本地 + 当前 Session，Session 结束可清除
- 用户关闭设置面板 = 默认无限次模式，不得强制设置额度
- 不依赖抖音/快手/B站官方 API（官方开放平台无法实时查询用户当前观看内容）

## 技术栈

- Kotlin + Jetpack Compose + Android SDK（首选）
- 核心系统能力：AccessibilityService、UsageStatsManager、前台服务、悬浮窗
- 首期禁止：Flutter / React Native / Java / 云端后端 / 大模型
- 数据来源 = Android 系统 + AccessibilityService + 本地状态，与第三方平台 API 无关

## 常用命令

工程位于 `xianying/` 子目录（Android Studio 向导生成后）：

```powershell
cd xianying
.\gradlew.bat assembleDebug                          # 构建 APK
.\gradlew.bat test                                   # 全部单元测试（纯 JVM，无需设备）
.\gradlew.bat test --tests "com.xianying.app.session.SessionManagerTest"   # 单个测试类
```

- APK 产物：`xianying/app/build/outputs/apk/debug/app-debug.apk`
- 首次构建需下载依赖，可能超过 10 分钟，超时属正常

## 测试与调试约束（重要）

- **测试顺序（2026-09-30 定）：模拟器先行，真机终验**。用 Android Studio 自带 AVD 模拟器（非虚拟机）做开发期主力自测：APK 安装、UI、状态机、无障碍事件流转、前台检测+回桌面——用**替身 App**（Chrome/设置）当目标 App 测，**模拟器上不装抖音**（抖音有模拟器检测）
- **真机只验模拟器验不了的**：抖音本体、厂商 ROM 杀后台/电池白名单、真实场景。真机不便连线，不依赖 adb/logcat：本机 `assembleDebug` 出 APK → 微信/QQ 传手机安装 → 用户按 `TESTING.md` 手测清单自测反馈
- **替代 logcat 的调试手段**：`MonitorAccessibilityService.recentEvents` 环形日志（最近 200 条窗口切换事件）显示在主界面调试区
- **单元测试全部纯 JVM**：`SessionManager` 是不依赖 Android 的纯 Kotlin 状态机，时间源走注入的 `Clock` 接口（实现必须基于 `elapsedRealtime` 单调时钟），测试用 `FakeClock` 模拟快进
- 每 Task 完成即 commit（实现计划中含各 Task 的提交信息）

## 核心业务模型

### Session 会话

每次进入短视频场景建立一个 Session，不跨长期保存。字段：targetApp / mode / maxVideos / maxDuration / watchedVideos / watchedDuration / awayDuration / status / warningTriggered。

- 离开目标 App → 暂停计时；重新进入 → 继续原 Session（awayDuration 归零），**不重弹设置面板**（每次新会话才弹）
- `awayDuration >= 30分钟` → Session 自动销毁，下次进入建立新 Session
- 在目标 App 内看直播/图文/评论区等非短视频内容 → 不计额度、会话保持、脱离计时清零
- 多个短视频 App 的 Session 相互独立（Session 含 targetApp 字段）

### 识别技术路线（已定稿）

**Accessibility 优先**：前台识别用 AccessibilityService 的 `TYPE_WINDOW_STATE_CHANGED` 事件（不用 UsageStats 轮询）；画面帧差/MediaProjection 录屏仅作 Accessibility 无法解决时的备选。

### 四种模式

仅条数 / 仅时长 / 无限次 / 双重限制（任一条件先达到即触发戒断：`count >= maxVideos OR duration >= maxDuration`）。

**MVP 只实现"仅时长 + 无限次"两种**（`ControlMode.DURATION / UNLIMITED`）；条数与双重限制为后续迭代。

### 有效计数规则

- 视频播放 **>= 5秒** 才计为 1 条有效视频；< 5 秒不计数
- 打开评论再返回原视频，不得错误计为新视频

### 戒断流程（分级柔性戒断）

剩余 1 条 / 1 分钟 → 顶部轻量提示（不遮挡、不打断）→ 额度用完 → 全屏警告 + 10 秒倒计时 + 震动 + 提示音 → 返回桌面结束使用场景 → **退出后无冷却锁定，可立即重新打开（重新弹面板）**。

**注意**：普通第三方 App 无法随意强制关闭其他 App（`forceStopPackage()` 不可假设可用）。已定稿基准：通过 Accessibility `GLOBAL_ACTION_HOME` 返回桌面。必须真机验证。

### 时长口径

目标语义：**只在视频播放时计时**（暂停不计）；MVP 降级为"目标 App 在前台即计时"，播放判定为后续增强。

## 架构约束

平台适配与业务逻辑必须解耦：

```
service/    AccessibilityMonitorService, AppMonitorService
session/    SessionManager, SessionState, SessionConfig
detector/   ShortVideoDetector (接口) → DouyinDetector / KuaishouDetector / BilibiliDetector
counter/    VideoCounter, DurationCounter
control/    WarningManager, ExitManager
ui/         MainScreen, LimitSettingScreen, WarningScreen
model/      TargetApp, ControlMode, SessionStatus
```

- **禁止**在 SessionManager 中写 `if douyin... if kuaishou...` 分支，必须走 Detector 接口（`isTargetApp` / `isShortVideoPage` / `isVideoChanged` / `getVideoInfo`）
- 首期只做抖音；禁止一开始同时开发三个平台

MVP 实际落地的结构（见实现计划，detector/counter 等留待后续迭代）：

```
com.xianying.app
├── MainActivity / ui          # 总开关 + 权限引导 + 调试日志
├── service/MonitorAccessibilityService   # 唯一事件入口：TYPE_WINDOW_STATE_CHANGED
├── service/KeepAliveForegroundService    # 前台服务：ticker(1s) + 常驻通知 + 震动/提示音
├── session/SessionManager     # 纯 Kotlin 状态机（注入 Clock，可 JVM 单测）
├── control/OverlayController  # 悬浮窗三件套：设置面板/预警条/全屏倒计时（统一 SAW）
├── control/ExitExecutor       # 倒计时归零 → GLOBAL_ACTION_HOME 返回桌面
├── runtime/Runtime            # 全局单例接线（clock/sessionManager/monitorService/overlay）
└── model/                     # TargetApp / ControlMode / SessionConfig
```

关键架构决策（设计文档第 4 节）：
- **前台识别不用 UsageStats**：Accessibility `TYPE_WINDOW_STATE_CHANGED` 直接拿前台包名，实时且少一个特殊权限；UsageStats 仅兜底
- **悬浮窗权限一举三得**：设置面板/预警条/全屏警告统一走 SYSTEM_ALERT_WINDOW
- **错误处理 fail-open**：工具故障绝不妨碍用户正常用机，失效即静默放行 + 通知提醒修复

## 开发流程

> **实施路线变更（设计文档已定稿）**：用户选择跳过独立的 Phase 0 验证工程，直接按实现计划（Task 1~9）开发 MVP，风险前置——监控核心（Accessibility/前台服务）最先真机验证，UI 壳靠后。下表为长期阶段参考，MVP 对应其中的时长模式闭环部分。

### 阶段计划（严格按序）

| Phase | 内容 |
|-------|------|
| 0 | 技术验证 Demo（1~2 天）：前台 App 识别、抖音识别、Accessibility 读取页面、页面变化监听、弹出全屏提示、辅助操作返回桌面。**不做完整 UI** |
| 1 | Session 创建/销毁、四种模式、30 分钟自动解除（模拟数据测试） |
| 2 | 时长模式完整闭环（进入计时→离开暂停→重进继续） |
| 3 | 抖音识别：前台识别、短视频页面识别、视频切换检测、5 秒有效计数 |
| 4 | 戒断：预警、全屏警告、倒计时、震动、提示音、返回桌面 |
| 5 | 快手 Detector |
| 6 | B站 Detector |

### MVP 定义（2026-09-30 修订版）

**阶段 0（零代码验证，先于任何开发）**：用 MacroDroid/Tasker 类工具验证「检测抖音前台 + 到点自动回桌面」两个假设，结论决定后续路线（详见设计文档第 0.3 节）。

**阶段 1（降级 MVP 闭环）**：打开限映预设时长 → 打开抖音 → 前台期间计时（离开暂停、重进继续）→ 累计到点 → 直接返回桌面。**此闭环在真机跑通即完成最关键技术验证。** 预警条/全屏倒计时/震动提示音/悬浮窗面板均为后续增强。

### 优先级

P0：Android 项目 / Session / 时长限制 / 前台 App 识别 / AccessibilityService
P1：抖音识别 / 视频切换 / 5 秒计数 / 预警 / 倒计时
P2：退出目标 App / 快手 / B站
P3：OCR / 视觉模型 / AI 辅助识别

## Claude Code 工作规则

1. 修改代码前先理解现有架构；不一次性重构整个项目；每次只完成一个明确功能
2. Android 系统能力存在不确定性时，**先写最小验证 Demo，不凭理论判断**；不假设某 API 一定有权限
3. 不假设可以强制关闭第三方 App；不未经验证修改抖音/快手/B站的包名或 UI 节点规则
4. 每完成一个功能进行编译和真机测试
5. 功能受 Android 系统限制时，明确记录为"系统限制"，**不得用虚假代码模拟成已实现**
6. 优先保证 MVP 可运行；不未经需求确认引入后端、数据库、账号系统或大模型
7. 传统规则能解决的问题优先用确定性规则，只有规则无法解决时才考虑 OCR / CV / Vision Model

## 已知技术风险（按序）

1. 短视频识别准确率（Accessibility 信息可能不足）
2. 视频切换识别（需区分新视频/评论/暂停/广告/直播/重复页面）
3. 自动退出目标 App（系统限制，须真机验证）
4. 后台长期运行（厂商后台限制、电池优化、服务被杀）
