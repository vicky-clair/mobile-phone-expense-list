package cn.foldledger.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore by preferencesDataStore("settings")
/** 应用设置的不可变快照；新增字段提供默认值，兼容已有 DataStore 文件。 */
data class AppSettings(
    val captureEnabled: Boolean = false, val sources: Set<String> = emptySet(),
    val successReminder: Boolean = false, val reviewReminder: Boolean = true,
    val lastConnected: Long = 0, val lastDisconnected: Long = 0, val lastParsed: Long = 0,
    val droppedCount: Int = 0, val captureErrors: Int = 0,
    val remindersEnabled: Boolean = true, val inAppReminder: Boolean = true
)
/** 持久化采集与提醒设置；读取以 Flow 发布，邮箱密钥由独立的 SecureMailConfig 管理。 */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    // 多个调用入口共享此锁，设置关闭与通知发布不能交错产生迟到提醒。
    companion object { val reminderMutex = Mutex() }
    private val store = context.settingsDataStore
    val flow = store.data.map { p -> AppSettings(
        p[booleanPreferencesKey("capture")] ?: false, p[stringSetPreferencesKey("sources")] ?: emptySet(),
        p[booleanPreferencesKey("success")] ?: false, p[booleanPreferencesKey("review")] ?: true,
        p[longPreferencesKey("connected")] ?: 0, p[longPreferencesKey("disconnected")] ?: 0,
        p[longPreferencesKey("parsed")] ?: 0, p[intPreferencesKey("dropped")] ?: 0,
        p[intPreferencesKey("errors")] ?: 0,
        p[booleanPreferencesKey("reminders")] ?: true,
        p[booleanPreferencesKey("inApp")] ?: true
    ) }
    /** 持久化开关并撤销对应已有提醒；与通知发布共用互斥锁，避免关闭之后又补发。 */
    suspend fun toggle(key: String, enabled: Boolean) = reminderMutex.withLock {
        store.edit { it[booleanPreferencesKey(key)] = enabled }
        if (!enabled) {
            val manager = appContext.getSystemService(android.app.NotificationManager::class.java)
            when (key) {
                "reminders" -> { manager.cancel(1); manager.cancel(2); cn.foldledger.capture.Reminders.cancelFeedback() }
                "review" -> manager.cancel(1)
                "success" -> manager.cancel(2)
                "inApp" -> cn.foldledger.capture.Reminders.cancelFeedback()
            }
        }
    }
    /** 增删单个授权来源，未选择的来源不会进入通知解析。 */
    suspend fun source(source: String, enabled: Boolean) {
        store.edit { p -> val key = stringSetPreferencesKey("sources")
            p[key] = (p[key] ?: emptySet()).let { if (enabled) it + source else it - source }
        }
    }
    /** 记录监听连接、断连或成功识别等事件的当前毫秒时间。 */
    suspend fun time(key: String) { store.edit { it[longPreferencesKey(key)] = System.currentTimeMillis() } }
    /** 原子累加队列溢出或采集错误计数，不记录通知正文。 */
    suspend fun increment(key: String, by: Int = 1) { store.edit { p -> val k = intPreferencesKey(key); p[k] = (p[k] ?: 0) + by } }
    /** 清空设置并恢复默认值，采集随默认关闭；全量删除入口还会清理系统通知。 */
    suspend fun reset() { store.edit { it.clear() } }
}
