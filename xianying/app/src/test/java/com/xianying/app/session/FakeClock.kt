package com.xianying.app.session

/** 测试专用假时钟：手动拨时间，模拟"过了 N 毫秒"。 */
class FakeClock(var t: Long = 0L) : Clock {
    override fun now(): Long = t
    fun advance(ms: Long) { t += ms }
}
