# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **设计文档**：`docs/superpowers/specs/2026-09-28-xianying-mvp-design.md`（MVP 设计，已定稿）
> **实现计划**：`docs/superpowers/plans/2026-09-28-xianying-mvp.md`（Task 1~9，含完整代码与测试）
> **真机手测清单**：`TESTING.md`（装 APK → 开权限 → 测试 A/B/C/D 四组用例 → 按格式反馈）

本文件是面向开发的项目要点与工作规则。

## 项目概述

**限映（XianYing）**：基于 Android 原生能力的本地短视频使用行为监测与自主管控工具。

核心理念：**由使用者自己决定本次短视频使用额度，通过单次 Session（会话）+ 分级柔性戒断进行自主管控，帮助建立自控习惯，而非粗暴限制。**

当前状态（2026-09-30）：**模拟器闭环全部验证通过（含真实抖音 APK）**。已实现超出原降级 MVP 的范围：仅条数模式（录屏采样 + 帧差识别切换）、三平台目标开关（抖音/快手/B站，含各发行版本包名）、分级戒断全套（剩 1 条/1 分钟预警 + 全屏警告 10 秒倒计时 + 震动提示音）、无障碍服务重绑自愈。**下一步：真机终验，按 `TESTING.md` 手测清单（测试 A 基本闭环 / B 保活 / C 快手B站 / D 条数+帧差）反馈结果。**

**硬约束（老师评审，优先级最高）**：不懂代码 + AI 写码 → 只走确定性路径（包名二元判断、确定性阈值），真机调试次数压到接近零。

Android 工程位于 `xianying/` 子目录（包名 `com.xianying.app`，minSdk 26，Kotlin DSL）。本目录是 git 仓库，**每完成一个功能即 commit**（提交信息见 git log，中文 conventional 风格）。`apps/` 下存放用于模拟器验证的真实抖音 APK。

## 产品红线（不可违背）

- 不做家长监控、不做云端统计、不保存历史使用记录、不做广告、不做社交、不做强制每日配额
- 不需要服务器、用户账号、数据库；所有数据仅存本地 + 当前 Session，Session 结束可清除
- 用户关闭设置面板 = 默认无限次模式，不得强制设置额度
- 不依赖抖音/快手/B站官方 API（官方开放平台无法实时查询用户当前观看内容）

## 技术栈

- Kotlin + Jetpack Compose + Android SDK（首选）
- 核心系统能力：AccessibilityService（前台识别）、前台服务（计时/通知/戒断）、MediaProjection（条数模式画面采样）
- 首期禁止：Flutter / React Native / Java / 云端后端 / 大模型
- 数据来源 = Android 系统 + AccessibilityService + 本地状态，与第三方平台 API 无关

## 常用命令

工程位于 `xianying/` 子目录（Android Studio 向导生成后）：

```powershell
cd xianying
.\gradlew.bat assembleDebug                          # 开发调试 APK
.\gradlew.bat assembleRelease                        # 真机测试用签名 APK
.\gradlew.bat test                                   # 全部单元测试（纯 JVM，无需设备）
.\gradlew.bat test --tests "com.xianying.app.session.SessionManagerTest"   # 单个测试类
```

- **真机 APK 产物**：`xianying/app/build/outputs/apk/release/app-release.apk`（release 已配自签：keystore `xianying/xianying-release.keystore`，密码在 `app/build.gradle.kts`；与 debug 签名不同，两者不能覆盖互装）
- 首次构建需下载依赖，可能超过 10 分钟，超时属正常

## 测试与调试约束（重要）

- **测试顺序（2026-09-30 定）：模拟器先行，真机终验**。用 Android Studio 自带 AVD 模拟器做开发期主力自测。早期用替身 App（Chrome/设置）当目标；后期已装真实抖音 APK（`apps/` 下的官方安装包）完成全闭环验证。注意抖音有模拟器检测，装前先确认该 APK 版本可用
- **真机只验模拟器验不了的**：厂商 ROM 杀后台/电池白名单、`GLOBAL_ACTION_HOME` 真机表现、快手/B站包名核对、帧差阈值真机调参。真机不便连线，不依赖 adb/logcat：本机 `assembleRelease` 出 APK → 微信/QQ 传手机安装 → 用户按 `TESTING.md` 手测清单自测反馈
- **替代 logcat 的调试手段**：`MonitorAccessibilityService.recentEvents` 环形日志（最近 200 条窗口切换事件）显示在主界面调试区
- **单元测试全部纯 JVM**：`SessionManager` 是不依赖 Android 的纯 Kotlin 状态机，时间源走注入的 `Clock` 接口（实现必须基于 `elapsedRealtime` 单调时钟），测试用 `FakeClock` 模拟快进
- 每 Task 完成即 commit（实现计划中含各 Task 的提交信息）

## 核心业务模型

### Session 会话

每次进入短视频场景建立一个 Session，不跨长期保存。字段：targetApp / mode / maxVideos / maxDuration / watchedVideos / watchedDuration / awayDuration / status / warningTriggered。

- 离开目标 App → 暂停计时；重新进入 → 继续原 Session（awayDuration 归零），额度不重置
- `awayDuration >= 30分钟` → Session 自动销毁，下次进入建立新 Session（额度重新满额）
- 在目标 App 内看直播/图文/评论区等非短视频内容 → 不计额度、会话保持、脱离计时清零
- 多个短视频 App 的 Session 相互独立（Session 含 targetApp 字段）

### 识别技术路线（已定稿 + 2026-09-30 实测修订）

- **前台识别**：AccessibilityService 的 `TYPE_WINDOW_STATE_CHANGED` 事件直接拿前台包名（不用 UsageStats 轮询）
- **视频切换识别（条数模式的眼睛）**：2026-09-30 全事件侦察实测确认**抖音信息流不发 `TYPE_VIEW_SCOLLED`**，播放动画造成持续 CONTENT_CHANGED 火龙——无障碍层面对"切了一条视频"无解。已走设计文档的备选路线：**MediaProjection 低清录屏采样（144×256 亮度网格，约 2.5 帧/秒）+ 帧差比对**（`FrameDiffDetector`，确定性阈值，不保存任何画面）。阈值经模拟器实测调参（changedRatio ≈ 0.35，在 `MediaProjectionService` 常量中，真机调参改这里）
- 已知风险点：帧差无法区分"切视频"与"评论区/个人页大变化"——TESTING.md 测试 D5/D6 专门验证误计率

### 四种模式

仅条数 / 仅时长 / 无限次 / 双重限制（任一条件先达到即触发戒断：`count >= maxVideos OR duration >= maxDuration`）。

**已实现前三种**（`ControlMode.DURATION / COUNT / UNLIMITED`）；双重限制为后续迭代。

### 有效计数规则

- 视频播放 **>= 5秒** 才计为 1 条有效视频；< 5 秒不计数
- 打开评论再返回原视频，不得错误计为新视频

### 戒断流程（分级柔性戒断，已实现）

剩余 1 条 / 1 分钟 → 通知栏预警（逐秒刷新）→ 额度用完 → 全屏警告页（`WarningActivity`，经全屏 Intent 通知拉起）+ 10 秒倒计时 + 震动 + 提示音 → 返回桌面结束使用场景 → **退出后无冷却锁定，可立即重新打开**。

**注意**：普通第三方 App 无法随意强制关闭其他 App（`forceStopPackage()` 不可假设可用）。基准方案：Accessibility `GLOBAL_ACTION_HOME` 返回桌面（模拟器已验证，真机待验）。警告页无"跳过/延长"按钮——10 秒缓冲期是规格定死的；倒计时归零由状态机统一执行，WarningActivity 只做可视化，状态离开 WARNING 即自动关闭。

### 时长口径

目标语义：**只在视频播放时计时**（暂停不计）；MVP 降级为"目标 App 在前台即计时"，播放判定为后续增强。

## 架构约束

平台适配与业务逻辑必须解耦（设计文档的长期目标结构含 per-platform Detector/Counter 接口分层；当前以包名表 + 通用帧差检测器达成同一目标，接口分层等真出现第二个识别实现时再引入）：

- **禁止**在 SessionManager 中写 `if douyin... if kuaishou...` 分支。当前平台差异收敛在两处：`TargetApp.packages`（包名表，二元判断）与 `FrameDiffDetector`（对所有平台通用的画面阈值）——尚未需要按平台分 Detector 实现
- 阈值/包名等"待真机验证"的值，改动必须有实测依据，不得凭理论拍脑袋

实际落地的结构（截至 2026-09-30）：

```
com.xianying.app
├── MainActivity / ui          # 总开关 + 三平台目标开关 + 额度输入 + 权限引导（①无障碍②通知③电池④画面识别）+ 调试日志
├── ui/WarningActivity         # 全屏警告页（全屏 Intent 通知拉起，只可视化不执行退出）
├── service/MonitorAccessibilityService   # 唯一事件入口：TYPE_WINDOW_STATE_CHANGED + recentEvents 环形日志(200条)
├── service/KeepAliveForegroundService    # 前台服务：ticker(1s) + 常驻通知（倒计时/暂停状态逐秒刷新）+ 震动提示音
├── service/MediaProjectionService        # 条数模式的眼睛：低清录屏采样 → FrameDiffDetector
├── detector/FrameDiffDetector            # 帧差切换检测器（纯 Kotlin 无 Android 依赖，可 JVM 单测）
├── session/SessionManager     # 纯 Kotlin 状态机（注入 Clock，可 JVM 单测）
├── control/ExitExecutor       # 倒计时归零 → GLOBAL_ACTION_HOME 返回桌面
├── runtime/Runtime            # 全局接线板：clock/sessionManager/monitorService/enabledTargets/restoreFromPrefs
└── model/                     # TargetApp（含各平台全部发行版本包名）/ ControlMode / SessionConfig
```

关键架构决策（设计文档第 4 节 + 实现期修订）：
- **前台识别不用 UsageStats**：Accessibility `TYPE_WINDOW_STATE_CHANGED` 直接拿前台包名，实时且少一个特殊权限；UsageStats 仅兜底
- **悬浮窗方案已被替换**：设置面板改为 App 内预设（打开目标 App 前先在限映里设好），预警/倒计时走通知栏，全屏警告走全屏 Intent 通知 + Activity——不申请 SYSTEM_ALERT_WINDOW
- **Runtime 单例接线**：无障碍服务与前台服务由系统分别创建，必须经 `Runtime` 对象互相找到；`restoreFromPrefs()` 幂等，服务被杀重绑后自动恢复设置并自愈拉起前台服务
- **错误处理 fail-open**：工具故障绝不妨碍用户正常用机，失效即静默放行 + 通知提醒修复

## 开发流程

> **实施路线（实际情况）**：跳过了独立的 Phase 0 验证工程与 MacroDroid 零代码验证，直接按实现计划开发，且实际交付超出降级 MVP 边界——分级戒断全套与条数模式均已实现并通过模拟器验证。原阶段表（设计文档）中 Phase 0~4 的内容已全部覆盖，Phase 5/6（快手/B站平台级识别细化）目前以包名表方式先行覆盖、未做页面级识别。

### 当前待办（真机终验驱动）

1. 用户按 `TESTING.md` 手测，回收 A/B/C/D 四组结果
2. 据结果处理：A5 失败 → 无障碍事件调试；B3 失败 → 自愈/保活加强；C1/C2 失败 → 修正对应平台包名；D5/D6 误计 → 调 `MediaProjectionService` 帧差阈值（模拟器初调 0.35）
3. 真机验证通过后，后续增强候选：双重限制模式、播放判定（暂停不计）、直播/评论区识别

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

## 已知技术风险（按序，前两项已有应对、待真机复核）

1. 帧差误计率：评论区开合、个人页/搜索页等"整屏大变化但非切视频"场景可能误计（TESTING.md 测试 D5/D6 专项验证；误计明显的临时方案 = 抖音只用仅时长模式）
2. 帧差阈值 0.35 是模拟器初调值，真机画质/帧率不同可能需重调（`MediaProjectionService` 顶部常量 + `lastChangedRatio` 诊断日志辅助调参）
3. `GLOBAL_ACTION_HOME` 在真机厂商 ROM 上能否把抖音送回桌面（模拟器已验证，真机待验）
4. 厂商 ROM 杀后台/电池优化导致服务被杀（已有重绑自愈 + 电池白名单引导，待测试 B 验证）
5. 快手/B站包名为公开常识值，未经真机验证（识别失败最多"不生效"，无其他影响）
