package com.cargps.mobile.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartup() {
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            // 作者：long｜启动布局只收集稳定首屏，避免把完整行程交互的 5 万余条规则全部推入 startup profile。
            device.prepareFreshApp()
            pressHome()
            startActivityAndWait()
            device.waitForText("开始行程")
        }
    }

    @Test
    fun generateCriticalUserJourneys() {
        baselineProfileRule.collect(PACKAGE_NAME) {
            device.prepareFreshApp()

            pressHome()
            startActivityAndWait()
            device.waitForText("开始行程")

            device.clickText("开始行程")
            device.waitForText("记录中")

            // 作者：long｜Home 后重新进入应用会覆盖活动前台服务、Activity 重绑和 ensure 路径，但不伪装成进程被杀恢复。
            pressHome()
            startActivityAndWait()
            device.waitForText("记录中")

            device.clickText("暂停行程")
            device.waitForText("继续行程")
            device.clickText("继续行程")
            device.waitForText("记录中")

            device.clickText("结束")
            device.waitForText("结束当前行程？")
            device.clickText("确认结束")
            device.waitForText("等待开始")
        }
    }

    private fun UiDevice.prepareFreshApp() {
        // 作者：long｜每个采集场景独立清数据并授权，避免测试顺序或上一段活动行程污染生成结果。
        executeShellCommand("pm clear $PACKAGE_NAME")
        executeShellCommand("pm grant $PACKAGE_NAME android.permission.ACCESS_COARSE_LOCATION")
        executeShellCommand("pm grant $PACKAGE_NAME android.permission.ACCESS_FINE_LOCATION")
        executeShellCommand("pm grant $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
    }

    private fun UiDevice.waitForText(text: String) {
        check(wait(Until.hasObject(By.text(text)), UI_TIMEOUT_MILLIS)) {
            "等待界面文本超时：$text"
        }
    }

    private fun UiDevice.clickText(text: String) {
        waitForText(text)
        findObject(By.text(text)).click()
    }

    companion object {
        private const val PACKAGE_NAME = "com.cargps.mobile"
        private const val UI_TIMEOUT_MILLIS = 10_000L
    }
}
