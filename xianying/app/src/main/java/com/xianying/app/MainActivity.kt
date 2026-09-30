package com.xianying.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xianying.app.runtime.Runtime
import com.xianying.app.service.KeepAliveForegroundService
import com.xianying.app.service.MonitorAccessibilityService
import com.xianying.app.session.Status
import com.xianying.app.ui.theme.限映Theme
import kotlinx.coroutines.delay

/**
 * 主界面（降级版 MVP）：总开关 + 预设额度 + 权限引导 + 会话状态 + 调试区。
 * 打开本页 = 激活 Runtime 接线并恢复上次的设置。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** onResume 自增：从系统设置返回后驱动权限状态重算。 */
        val refreshTick = mutableStateOf(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Runtime.appContext = applicationContext
        // 恢复上次设置（重启手机后打开一次本页即恢复监控）
        val sp = getSharedPreferences("xianying", Context.MODE_PRIVATE)
        Runtime.targetOverride = sp.getString("override_pkg", "")?.ifBlank { null }
        Runtime.sessionManager.configure(sp.getInt("minutes", 15) * 60_000L)
        if (sp.getBoolean("master", false)) {
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

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val sp = ctx.getSharedPreferences("xianying", Context.MODE_PRIVATE)

    var master by remember { mutableStateOf(sp.getBoolean("master", false)) }
    var minutes by remember { mutableStateOf(sp.getInt("minutes", 15).toString()) }
    var overridePkg by remember { mutableStateOf(sp.getString("override_pkg", "") ?: "") }
    var snap by remember { mutableStateOf(Runtime.sessionManager.snapshot()) }
    @Suppress("UNUSED_EXPRESSION") MainActivity.refreshTick.value   // 读取以触发重组刷新权限状态

    // 通知权限（Android 13+ 需运行时申请）
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

    fun applyQuota() {
        val m = minutes.toLongOrNull() ?: 0L
        sp.edit().putInt("minutes", m.toInt()).apply()
        Runtime.sessionManager.configure(if (m > 0) m * 60_000 else 0L)
    }

    Column(
        Modifier.fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {

        // ---- 标题 + 总开关 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("限映", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.weight(1f))
            Text("总开关")
            Switch(checked = master, onCheckedChange = { on ->
                master = on
                sp.edit().putBoolean("master", on).apply()
                if (on) {
                    applyQuota()
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
        Text(
            if (master) "监控已开启：进入抖音即开始计时" else "监控已关闭",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(16.dp))

        // ---- 预设额度 ----
        Text("每次额度（分钟）", style = MaterialTheme.typography.titleMedium)
        Text("新会话生效；进行中的会话不受影响", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = minutes,
            onValueChange = { v ->
                minutes = v.filter { it.isDigit() }.take(3)
                applyQuota()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        // ---- 当前会话状态 ----
        Text("当前会话", style = MaterialTheme.typography.titleMedium)
        Text(
            when (snap.status) {
                Status.IDLE -> "空闲（未计时）"
                Status.TIMING -> "计时中 · 剩余 ${snap.remainingMs / 60_000} 分 ${snap.remainingMs % 60_000 / 1000} 秒"
                Status.PAUSED -> "已暂停 · 剩余 ${snap.remainingMs / 60_000} 分 ${snap.remainingMs % 60_000 / 1000} 秒（额度保留）"
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---- 权限引导 ----
        Text("权限状态", style = MaterialTheme.typography.titleMedium)
        val a11yOk = accessibilityEnabled(ctx)
        val notifOk = ctx.getSystemService(android.app.NotificationManager::class.java)
            .areNotificationsEnabled()
        val batteryOk = ctx.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(ctx.packageName)
        PermissionRow("① 无障碍服务（必须——识别与返回桌面）", a11yOk) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermissionRow("② 通知（显示剩余时间）", notifOk) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppDetails(ctx)
            }
        }
        PermissionRow("③ 电池优化白名单（建议——防杀后台）", batteryOk) {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}"))
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---- 调试区：替身包名 + 事件日志 ----
        Text("调试区（模拟器测试用）", style = MaterialTheme.typography.titleMedium)
        Text("替身包名：留空 = 正式模式（抖音）", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = overridePkg,
            onValueChange = { v ->
                overridePkg = v.trim()
                sp.edit().putString("override_pkg", overridePkg).apply()
                Runtime.targetOverride = overridePkg.ifBlank { null }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("如 com.android.chrome") },
        )

        Spacer(Modifier.height(8.dp))
        Text("窗口切换事件日志（最近 30 条）", style = MaterialTheme.typography.titleSmall)
        Text(
            MonitorAccessibilityService.recentEvents.toList().takeLast(30)
                .joinToString("\n") { it },
            style = MaterialTheme.typography.bodySmall,
        )
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
