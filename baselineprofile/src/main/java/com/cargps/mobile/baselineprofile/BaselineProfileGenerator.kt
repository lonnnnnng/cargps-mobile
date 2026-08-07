package com.cargps.mobile.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(PACKAGE_NAME) {
            // 作者：long｜首屏和仪表组件是每次驾驶都会经过的热路径，基线只覆盖稳定入口，不自动操作定位权限。
            pressHome()
            startActivityAndWait()
        }
    }

    companion object {
        private const val PACKAGE_NAME = "com.cargps.mobile"
    }
}
