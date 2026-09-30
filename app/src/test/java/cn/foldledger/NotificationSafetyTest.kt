package cn.foldledger

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import cn.foldledger.capture.Sources
import cn.foldledger.capture.snapshotPaymentNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 通知读取的行为回归：保持原通知及动作不变，不执行通知上的支付入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotificationSafetyTest {
    private fun notification(packageName: String = Sources.WECHAT, summary: Boolean = false): StatusBarNotification {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val action = PendingIntent.getBroadcast(app, 10, Intent("cn.foldledger.TEST_PAYMENT_ACTION"), PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(app, "payment-source")
            .setContentTitle("微信支付").setContentText("支付成功：￥12.30")
            .setContentIntent(action).setDeleteIntent(action).setGroupSummary(summary)
            .addAction(Notification.Action.Builder(null, "支付动作", action).build()).build()
        return StatusBarNotification(packageName, packageName, 20, "transaction", 1000, 0, 0, note, Process.myUserHandle(), 42L)
    }
    @Test fun captureDoesNotMutateSourceOrExecutePaymentAction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = notification()
        val flags = source.notification.flags
        val action = source.notification.contentIntent
        val count = shadowOf(app).broadcastIntents.size
        repeat(10) {
            val result = requireNotNull(snapshotPaymentNotification(source, setOf(Sources.WECHAT)))
            assertEquals("支付成功：￥12.30", result.text)
            assertEquals(42L, result.postTime)
        }
        assertEquals(count, shadowOf(app).broadcastIntents.size)
        assertEquals(flags, source.notification.flags)
        assertSame(action, source.notification.contentIntent)
        assertSame(action, source.notification.deleteIntent)
        assertSame(action, source.notification.actions.single().actionIntent)
        assertEquals("支付成功：￥12.30", source.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }
    @Test fun unselectedSourcesAndSummaryNotificationsAreIgnored() {
        assertNull(snapshotPaymentNotification(notification(), emptySet()))
        assertNull(snapshotPaymentNotification(notification("com.unknown.bank"), setOf("com.unknown.bank")))
        assertNull(snapshotPaymentNotification(notification(summary = true), setOf(Sources.WECHAT)))
    }
    @Test fun boundedSnapshotLeavesLongOriginalTextUntouched() {
        val source = notification()
        source.notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "x".repeat(4000))
        val result = requireNotNull(snapshotPaymentNotification(source, setOf(Sources.WECHAT)))
        assertEquals(2000, result.text.length)
        assertEquals(4000, source.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.length)
    }
}
