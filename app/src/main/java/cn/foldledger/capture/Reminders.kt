package cn.foldledger.capture

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import cn.foldledger.MainActivity
import cn.foldledger.R
import cn.foldledger.data.*
import cn.foldledger.domain.Status
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/** 应用提醒的统一出口；通知正文不含金额和商户，总开关与分类开关共同生效。 */
object Reminders {
    private var feedback: android.widget.Toast? = null
    /** 取消仍在显示的操作 Toast 并释放引用。 */
    fun cancelFeedback() { feedback?.cancel(); feedback = null }
    /** 操作结果 Toast 受总开关和操作提示开关控制；与关闭开关串行执行。 */
    suspend fun feedback(context: Context, settings: SettingsStore, message: String) = SettingsStore.reminderMutex.withLock {
        val prefs = settings.flow.first()
        if (prefs.remindersEnabled && prefs.inAppReminder) {
            cancelFeedback()
            feedback = android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_LONG).also { it.show() }
        }
    }
    /** 根据审核状态选择提醒分类，逐项检查应用开关、系统权限与通知渠道；返回是否提交通知。 */
    suspend fun show(context: Context, entry: LedgerEntry, settings: SettingsStore): Boolean = SettingsStore.reminderMutex.withLock {
        val p = settings.flow.first()
        val review = entry.status in setOf(Status.NEEDS_REVIEW.name, Status.POSSIBLE_DUPLICATE.name)
        if (!p.remindersEnabled || !(if (review) p.reviewReminder else p.successReminder)) return@withLock false
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withLock false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return@withLock false
        manager.createNotificationChannel(NotificationChannel("ledger_private", "账本提醒", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setSound(null, null); enableVibration(false)
        })
        if (manager.getNotificationChannel("ledger_private").importance == NotificationManager.IMPORTANCE_NONE) return@withLock false
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(if (review) 1 else 2, NotificationCompat.Builder(context, "ledger_private")
            .setSmallIcon(R.drawable.ic_ledger).setContentTitle(if (review) "有账目需要核对" else "账本已更新")
            .setContentText("解锁费用统计清单后查看").setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(intent).setAutoCancel(true).setSilent(true).build())
        true
    }
}
