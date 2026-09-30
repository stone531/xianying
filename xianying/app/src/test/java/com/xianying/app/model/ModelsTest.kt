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
        assertNull(TargetApp.fromPackage(null))
    }
}
