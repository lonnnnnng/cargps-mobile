package com.cargps.mobile

import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.cargps.DashboardState
import com.cargps.FixStatus
import com.cargps.TripMode
import com.cargps.mobile.ui.MobileDashboardScreen
import com.cargps.mobile.ui.MobileGpsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripAccessInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<CarGpsApplication>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun forcePortraitTestWindow() {
        // 作者：long｜通用 Compose 测试宿主不读取 MainActivity 的竖屏声明，必须固定为竖屏，避免 API 27 横屏 AVD 把手机版底部权限入口误判为不可见。
        device.setOrientationNatural()
        device.waitForIdle()
        if (device.displayWidth > device.displayHeight) {
            device.setOrientationLeft()
            device.waitForIdle()
        }
    }

    @After
    fun restoreDeviceRotation() {
        device.unfreezeRotation()
    }

    @Test
    fun settingsIntentsTargetCurrentApplicationAndSystemLocation() {
        val appSettings = appSettingsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appSettings.action)
        assertEquals(Uri.fromParts("package", context.packageName, null), appSettings.data)

        val locationSettings = locationSettingsIntent()
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, locationSettings.action)

        val notificationSettings = notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notificationSettings.action)
        assertEquals(
            context.packageName,
            notificationSettings.getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )
    }

    @Test
    fun initialLocationBlockKeepsRequiredInformationOnOneScreen() {
        setDashboardContent(
            state = DashboardState(
                fixStatus = FixStatus.PERMISSION_REQUIRED,
                storageReady = true,
            ),
            access = locationPermissionRequired(),
        )

        composeRule.onNodeWithText("需要定位授权").assertIsDisplayed()
        composeRule.onNodeWithText("允许精确定位").assertIsDisplayed()
        composeRule.onNodeWithText("经纬度", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("位置因子", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("报文类型", substring = true).assertIsDisplayed()
        composeRule.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    @Test
    fun activeTripBlockKeepsEndActionAvailable() {
        var endTripCalls = 0
        setDashboardContent(
            state = DashboardState(
                fixStatus = FixStatus.PERMISSION_REQUIRED,
                tripMode = TripMode.RECORDING,
                storageReady = true,
            ),
            access = locationPermissionRequired(),
            onEndTrip = { endTripCalls += 1 },
        )

        composeRule.onNodeWithText("记录受阻").assertIsDisplayed()
        composeRule.onNodeWithText("允许精确定位").assertIsDisplayed()
        composeRule.onNodeWithText("结束").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("结束当前行程？").assertIsDisplayed()
        composeRule.onNodeWithText("确认结束").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, endTripCalls) }
    }

    private fun setDashboardContent(
        state: DashboardState,
        access: TripAccessState,
        onEndTrip: () -> Unit = {},
    ) {
        // 作者：long｜直接注入权限阻断状态，避免系统授权弹窗差异掩盖单屏布局和结束行程的产品约束。
        composeRule.setContent {
            MobileGpsTheme(darkTheme = state.darkTheme) {
                MobileDashboardScreen(
                    state = state,
                    tripAccessState = access,
                    onResolveTripAccess = {},
                    onToggleTrip = {},
                    onEndTrip = onEndTrip,
                    onToggleTheme = {},
                )
            }
        }
    }

    private fun locationPermissionRequired() = TripAccessState.Blocked(
        blocker = TripAccessBlocker.LOCATION_PERMISSION_REQUIRED,
        resolution = TripAccessResolution.REQUEST_LOCATION_PERMISSION,
    )
}
