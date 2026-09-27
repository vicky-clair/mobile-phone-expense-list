package cn.foldledger

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import cn.foldledger.capture.Reminders
import cn.foldledger.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
/** 使用 Robolectric 验证系统通知和 Toast 的开关行为，不依赖真实通知栏。 */
class RemindersTest {
    private lateinit var app: Application
    private lateinit var settings: SettingsStore
    private lateinit var manager: NotificationManager
    /** 生成待确认或已确认测试账目，仅传入提醒出口，不写数据库。 */
    private fun entry(review: Boolean) = LedgerEntry("test", 1230, direction = "EXPENSE", merchant = "秘密商户", channel = "模拟",
        occurredAt = 1L, status = if (review) "NEEDS_REVIEW" else "CONFIRMED")
    /** 授权模拟通知权限并重置设置、通知和 Toast，保持用例隔离。 */
    @Before fun setup() = runTest {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        settings = SettingsStore(app); settings.reset(); settings.toggle("success", true)
        Reminders.cancelFeedback(); ShadowToast.reset()
    }
    /** 总开关关闭撤销已有提醒并阻止新通知，但不能关闭记账采集。 */
    @Test fun masterOffCancelsExistingAndBlocksFutureNotifications() = runTest {
        assertTrue(Reminders.show(app, entry(false), settings)); assertTrue(Reminders.show(app, entry(true), settings))
        assertEquals(2, manager.activeNotifications.size)
        settings.toggle("capture", true)
        settings.toggle("reminders", false)
        assertEquals(0, manager.activeNotifications.size)
        assertFalse(Reminders.show(app, entry(false), settings)); assertFalse(Reminders.show(app, entry(true), settings))
        assertTrue(settings.flow.first().captureEnabled)
        assertFalse(SettingsStore(app).flow.first().remindersEnabled)
    }
    /** 分类开关只清理对应通知，重开总开关仍保留分类偏好。 */
    @Test fun categorySwitchCancelsOnlyItsOwnNotification() = runTest {
        Reminders.show(app, entry(false), settings); Reminders.show(app, entry(true), settings)
        settings.toggle("review", false)
        assertEquals(listOf(2), manager.activeNotifications.map { it.id })
        assertFalse(Reminders.show(app, entry(true), settings))
        settings.toggle("success", false)
        assertTrue(manager.activeNotifications.isEmpty())
        settings.toggle("reminders", false); settings.toggle("reminders", true)
        assertFalse(Reminders.show(app, entry(false), settings)) // 总开关不得覆盖各分类偏好。
    }
    /** 并发发布与关闭必须串行，关闭操作完成后不能残留迟到通知。 */
    @Test fun concurrentDeliveryCannotRacePastMute() = runTest {
        (1..10).map { async { Reminders.show(app, entry(true), settings) } }.awaitAll()
        listOf(async { settings.toggle("reminders", false) }, async { Reminders.show(app, entry(true), settings) }).awaitAll()
        assertTrue(manager.activeNotifications.isEmpty())
    }
    /** 系统拒绝通知权限时返回未发布，不尝试绕过权限。 */
    @Test fun deniedPermissionProducesNoNotification() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Reminders.show(app, entry(true), settings))
        assertTrue(manager.activeNotifications.isEmpty())
    }
    /** 操作 Toast 同时受总开关和操作提示开关控制。 */
    @Test fun operationFeedbackHonorsBothSwitches() = runTest {
        settings.toggle("inApp", false)
        Reminders.feedback(app, settings, "导出成功")
        assertEquals(0, ShadowToast.shownToastCount())
        settings.toggle("inApp", true); settings.toggle("reminders", false)
        Reminders.feedback(app, settings, "导出成功")
        assertEquals(0, ShadowToast.shownToastCount())
        settings.toggle("reminders", true)
        Reminders.feedback(app, settings, "导出成功")
        assertEquals("导出成功", ShadowToast.getTextOfLatestToast())
    }
}
