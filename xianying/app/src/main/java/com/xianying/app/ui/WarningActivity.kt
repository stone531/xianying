package com.xianying.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianying.app.runtime.Runtime
import com.xianying.app.session.Status
import com.xianying.app.ui.theme.限映Theme
import kotlinx.coroutines.delay

/**
 * 全屏警告页——额度用完后的 10 秒缓冲期（分级戒断第二级）。
 * 由前台服务的全屏 Intent 通知拉起；倒计时归零由状态机统一执行返回桌面，
 * 本页只是可视化：状态离开 WARNING（退出发作/用户自己离开）即自动关闭。
 * 无"跳过/延长"按钮——缓冲期是规格定死的 10 秒。
 */
class WarningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            限映Theme {
                Surface(Modifier.fillMaxSize(), color = Color(0xE6101010)) {
                    var leftMs by remember {
                        mutableLongStateOf(Runtime.sessionManager.snapshot().warningRemainingMs)
                    }
                    // 跟随状态机倒计时；状态一旦离开 WARNING 立即关页
                    LaunchedEffect(Unit) {
                        while (true) {
                            val s = Runtime.sessionManager.snapshot()
                            if (s.status != Status.WARNING) {
                                finish()
                                break
                            }
                            leftMs = s.warningRemainingMs
                            delay(100)
                        }
                    }
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("⏰", fontSize = 64.sp)
                        Text(
                            "本次额度已用完",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier
                                .size(160.dp)
                                .background(Color(0xFFF44336), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${(leftMs + 999) / 1000}",
                                fontSize = 80.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("秒后自动返回桌面", fontSize = 18.sp, color = Color(0xFFB0B0B0))
                        Text(
                            "稍后可重新打开，自主决定是否继续",
                            fontSize = 14.sp,
                            color = Color(0xFF808080),
                        )
                    }
                }
            }
        }
    }
}
