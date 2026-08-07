package com.cargps.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileDashboardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardKeepsCoreTelemetryVisibleWithoutScrolling() {
        composeRule.onNodeWithText("CAR GPS").assertIsDisplayed()
        composeRule.onNodeWithText("经纬度", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("位置因子", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("报文类型", substring = true).assertIsDisplayed()

        // 作者：long｜手机版验收要求首屏完整展示，出现任何滚动节点都意味着固定区域重新发生溢出。
        composeRule.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }
}
