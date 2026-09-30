package com.xianying.app.session

/**
 * 时间源接口。
 * 实现必须基于单调时钟（elapsedRealtime）：用户改系统时间也不影响计时。
 * 测试用 FakeClock 手动拨时间，就能在电脑上"快进"验证半小时后的行为。
 */
interface Clock { fun now(): Long }

class SystemElapsedClock : Clock {
    override fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
