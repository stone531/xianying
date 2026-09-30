package com.xianying.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianying.app.model.TargetApp
import com.xianying.app.runtime.Runtime
import com.xianying.app.service.KeepAliveForegroundService
import com.xianying.app.service.MonitorAccessibilityService
import com.xianying.app.session.Status
import com.xianying.app.ui.theme.限映Theme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    companion object {
        /** onResume 自增：从系统设置返回后驱动权限状态重算。 */
        val refreshTick = mutableStateOf(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 恢复上次设置（重启手机后打开一次本页即恢复监控）
        if (Runtime.restoreFromPrefs(this)) {
            KeepAliveForegroundService.start(this)
        }
        setContent {
            限映Theme {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.value++
    }
}

private fun saveTarget(sp: SharedPreferences, app: TargetApp, on: Boolean) {
    sp.edit().putBoolean("target_${app.name}", on).apply()
    Runtime.enabledTargets = TargetApp.entries.filter {
        sp.getBoolean("target_${it.name}", it == TargetApp.DOUYIN)
    }.toSet()
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val sp = ctx.getSharedPreferences("xianying", Context.MODE_PRIVATE)

    var master by remember { mutableStateOf(sp.getBoolean("master", false)) }
    var minutes by remember { mutableStateOf(sp.getInt("minutes", 10).coerceAtLeast(0).toString()) }
    var snap by remember { mutableStateOf(Runtime.sessionManager.snapshot()) }
    @Suppress("UNUSED_EXPRESSION") MainActivity.refreshTick.value   // 读取以触发重组刷新权限状态

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 每秒刷新会话状态 + 事件日志（仅本界面可见时刷新）
    LaunchedEffect(Unit) {
        while (true) {
            snap = Runtime.sessionManager.snapshot()
            delay(1_000)
        }
    }

    /** 额度自由输入（规格：无预设选项）：仅数字，最多 3 位；空 = 不设限（无限次模式）。 */
    fun applyQuota(input: String) {
        val f = input.filter { it.isDigit() }.take(3)
        minutes = f
        val n = f.toIntOrNull() ?: 0
        sp.edit().putInt("minutes", n).apply()
        Runtime.sessionManager.configure(if (n > 0) n * 60_000L else 0L)
    }

    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ---- 标题 ----
        Text("限映", style = MaterialTheme.typography.headlineLarge)
        Text(
            "短视频使用额度 · 自主管控",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---- 总开关（大卡片）----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (master)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                Modifier.padding(20.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "监控总开关",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (master) "已开启 · 进入目标 App 即开始计时"
                        else "已关闭 · 开启前可修改目标与额度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = master, onCheckedChange = { on ->
                    master = on
                    sp.edit().putBoolean("master", on).apply()
                    if (on) {
                        KeepAliveForegroundService.start(ctx)
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        KeepAliveForegroundService.stop(ctx)
                        Runtime.sessionManager.reset()
                    }
                })
            }
        }

        // ---- 监控目标（总开关开启时锁定）----
        SectionCard("监控目标", if (master) "关闭总开关后可修改" else "开启即监控对应 App") {
            TargetApp.entries.forEach { app ->
                val on = app in Runtime.enabledTargets
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(app.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (app == TargetApp.DOUYIN) "极速/火山包号待验证" else "包号待真机验证",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Text(
                            app.packages.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = on,
                        enabled = !master,      // 总开关开启时锁定
                        onCheckedChange = { saveTarget(sp, app, it) }
                    )
                }
            }
        }

        // ---- 每次额度（总开关开启时锁定；规格：自由输入，无预设选项）----
        SectionCard("每次额度", if (master) "关闭总开关后可修改" else "对下一次进入目标 App 生效") {
            OutlinedTextField(
                value = minutes,
                onValueChange = { applyQuota(it) },
                enabled = !master,             // 总开关开启时锁定
                label = { Text("分钟数") },
                suffix = { Text("分钟") },
                supportingText = { Text("1~999 自由输入；留空 = 不设限（无限次模式）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        // ---- 当前会话 ----
        SectionCard("当前会话", null) {
            val (dot, text) = when (snap.status) {
                Status.IDLE -> Color(0xFF9E9E9E) to "空闲 · 未计时"
                Status.TIMING -> Color(0xFF4CAF50) to
                    "计时中 · 剩余 ${snap.remainingMs / 60_000} 分 ${snap.remainingMs % 60_000 / 1000} 秒"
                Status.PAUSED -> Color(0xFFFF9800) to
                    "已暂停 · 剩余 ${snap.remainingMs / 60_000} 分 ${snap.remainingMs % 60_000 / 1000} 秒（额度保留）"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(dot, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }

        // ---- 权限 ----
        SectionCard("权限状态", null) {
            val a11yOk = accessibilityEnabled(ctx)
            val notifOk = ctx.getSystemService(NotificationManager::class.java)
                .areNotificationsEnabled()
            val batteryOk = ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName)
            PermissionRow("① 无障碍服务（识别与返回桌面）", a11yOk) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            PermissionRow("② 通知（显示剩余时间）", notifOk) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppDetails(ctx)
                }
            }
            PermissionRow("③ 电池优化白名单（防杀后台）", batteryOk) {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}"))
                )
            }
        }

        // ---- 调试：事件日志 ----
        SectionCard("窗口切换事件（调试，最近 30 条）", null) {
            Text(
                MonitorAccessibilityService.recentEvents.toList().takeLast(30)
                    .joinToString("\n") { it },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 统一的分区卡片：标题 + 副标题（可空）+ 内容。 */
@Composable
private fun SectionCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✅" else "❌", Modifier.padding(end = 8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = onFix) { Text("去开启") }
    }
}

private fun accessibilityEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(AccessibilityManager::class.java)
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
}

private fun openAppDetails(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"))
    )
}
