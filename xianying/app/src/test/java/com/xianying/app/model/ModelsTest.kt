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
    fun kuaishou_package_resolves() {
        // 包号待真机验证：若与实际不符只需改枚举这一处
        assertEquals(TargetApp.KUAISHOU, TargetApp.fromPackage("com.smile.gifmaker"))
    }

    @Test
    fun xiaohongshu_package_resolves() {
        assertEquals(TargetApp.XIAOHONGSHU, TargetApp.fromPackage("com.xingin.xhs"))
    }

    @Test
    fun unknown_package_returns_null() {
        assertNull(TargetApp.fromPackage("com.tencent.mm"))
        assertNull(TargetApp.fromPackage(null))
    }
}
