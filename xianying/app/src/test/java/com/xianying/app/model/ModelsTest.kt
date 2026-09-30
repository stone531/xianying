package com.xianying.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun douyin_all_versions_resolve() {
        // 正式版（已模拟器验证）
        assertEquals(TargetApp.DOUYIN, TargetApp.fromPackage("com.ss.android.ugc.aweme"))
        // 极速版
        assertEquals(TargetApp.DOUYIN, TargetApp.fromPackage("com.ss.android.ugc.aweme.lite"))
        // 火山版
        assertEquals(TargetApp.DOUYIN, TargetApp.fromPackage("com.ss.android.ugc.live"))
        // 精选（前身青桃，中长视频）与商城（原商城版，购物）——包名经应用宝/小米商店核实
        assertEquals(TargetApp.DOUYIN, TargetApp.fromPackage("com.ss.android.yumme.video"))
        assertEquals(TargetApp.DOUYIN, TargetApp.fromPackage("com.ss.android.ugc.livelite"))
    }

    @Test
    fun kuaishou_all_versions_resolve() {
        // 包号待真机验证：若与实际不符只需改枚举这一处
        assertEquals(TargetApp.KUAISHOU, TargetApp.fromPackage("com.smile.gifmaker"))
        assertEquals(TargetApp.KUAISHOU, TargetApp.fromPackage("com.kuaishou.nebula"))
    }

    @Test
    fun bilibili_all_versions_resolve() {
        // 包号待真机验证
        assertEquals(TargetApp.BILIBILI, TargetApp.fromPackage("tv.danmaku.bili"))
        assertEquals(TargetApp.BILIBILI, TargetApp.fromPackage("com.bstar.intl"))
    }

    @Test
    fun unknown_package_returns_null() {
        assertNull(TargetApp.fromPackage("com.tencent.mm"))
        assertNull(TargetApp.fromPackage("com.xingin.xhs"))   // 小红书已按需求移出监控范围
        assertNull(TargetApp.fromPackage(null))
        assertNull(TargetApp.fromPackage(""))
    }

    @Test
    fun no_overlap_between_platforms() {
        // 三平台的包名集合互不相交，保证一个包名只归属一个平台
        val all = TargetApp.entries.flatMap { it.packages }
        assertEquals(all.size, all.toSet().size)
        assertTrue(TargetApp.entries.size == 3)   // 抖音、快手、B站
    }
}
