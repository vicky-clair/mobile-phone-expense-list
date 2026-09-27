package cn.foldledger.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cn.foldledger.LedgerApp
import cn.foldledger.domain.NotificationPayload
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicInteger

/** 系统通知监听入口；回调只过滤和入队，解析、数据库写入及提醒在后台消费。 */
class PaymentListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 固定容量限制瞬时内存；消费速度不足时记录丢弃计数，不无限扩容。
    private val queue = Channel<NotificationPayload>(64)
    private val dropped = AtomicInteger(0)
    private val app get() = application as LedgerApp
    // 系统回调与后台配置订阅跨线程读取，使用可见性保证同步最新白名单。
    @Volatile private var enabledSources: Set<String> = emptySet()
    /** 订阅来源配置并启动单消费者；队列有界，溢出只累加计数，避免通知突发导致无限任务。 */
    override fun onCreate() {
        super.onCreate()
        scope.launch { app.settings.flow.collectLatest { enabledSources = if (it.captureEnabled) it.sources else emptySet() } }
        scope.launch {
            for (payload in queue) {
                try {
                    val overflow = dropped.getAndSet(0)
                    if (overflow > 0) app.settings.increment("dropped", overflow)
                    app.repository.ingest(payload)?.let { Reminders.show(this@PaymentListener, it, app.settings) }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { app.settings.increment("errors") }
            }
        }
    }
    /** 先检查白名单和组汇总，再截取有限长度文本；非阻塞入队，禁止在系统回调中做网络操作。 */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in enabledSources || sbn.packageName !in Sources.names) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(200) ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(2000) ?: ""
        val payload = NotificationPayload(sbn.packageName, sbn.key, title, text, sbn.postTime)
        if (!queue.trySend(payload).isSuccess) dropped.incrementAndGet()
    }
    /** 记录监听连接时间，供设置页判断最近状态。 */
    override fun onListenerConnected() { scope.launch { app.settings.time("connected") } }
    /** 记录断连并向系统请求重新绑定，不承诺强行保活或补回所有历史通知。 */
    override fun onListenerDisconnected() {
        scope.launch { app.settings.time("disconnected") }
        requestRebind(ComponentName(this, PaymentListener::class.java))
    }
    /** 关闭队列并取消服务协程，释放与本次监听生命周期绑定的资源。 */
    override fun onDestroy() { queue.close(); scope.cancel(); super.onDestroy() }
}
