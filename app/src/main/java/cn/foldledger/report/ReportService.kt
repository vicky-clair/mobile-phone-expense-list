package cn.foldledger.report

import android.util.AtomicFile
import cn.foldledger.data.LedgerDao
import cn.foldledger.domain.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.util.UUID


/** 日报业务协调器；同一互斥锁串行化生成、发送、配置修改及删除。 */
class ReportService(
    private val configStore: MailConfigStore,
    private val dao: ReportDao,
    private val ledger: LedgerDao,
    private val folder: File,
    private val mailer: ReportMailer = SmtpMailer()
) {
    private val mutex = Mutex()
    val history get() = dao.history()
    /** 在业务锁内读取加密配置，避免与保存或清除交错。 */
    suspend fun config(): MailConfig = mutex.withLock { configStore.read() }
    /** 有未完成邮件时禁止更换收件人；首次或重新启用从当天开始生成。 */
    suspend fun save(config: MailConfig) = mutex.withLock {
        config.validate()
        val previous = configStore.read()
        require(previous.recipient == config.recipient || dao.outstandingCount() == 0) {
            "还有未完成邮件，请先处理发送记录，再修改收件地址"
        }
        val next = if (previous.enabled && previous.nextDate.isNotBlank()) previous.nextDate else DailyCsv.today().toString()
        configStore.write(config.copy(nextDate = next))
    }
    /** 限制报告 ID 字符并定位应用私有目录，防止路径穿越。 */
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        check(folder.isDirectory || folder.mkdirs())
        return File(folder, "$id.csv")
    }
    /** 使用 AtomicFile 写入快照，写入失败回滚，不留下半个有效 CSV。 */
    private fun writeSnapshot(id: String, text: String) {
        val atomic = AtomicFile(file(id))
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    /** 删除该报告的本机文件并检查结果，不删除原账本或邮箱副本。 */
    private fun deleteSnapshot(id: String) {
        AtomicFile(file(id)).delete()
        check(!file(id).exists()) { "日报清理失败" }
    }
    /** 仅对已接受邮件清理文件，完成后再持久化 cleaned；失败可恢复清理。 */
    private suspend fun cleanupAccepted(report: DailyReport) {
        deleteSnapshot(report.id)
        dao.update(report.copy(cleaned = true, detail =
            if (report.detail.startsWith("用户确认")) "用户确认已收到；本机日报文件已清理"
            else "邮件服务器已接收；本机日报文件已清理"))
    }
    /** 根据当前账本预览昨日内容，不创建待发文件；可能与历史已生成快照不同。 */
    suspend fun preview(): String = mutex.withLock {
        val day = DailyCsv.today().minusDays(1)
        val (start, end) = DailyCsv.bounds(day)
        DailyCsv.render(day, ledger.reportRows(start, end))
    }
    /** 逐日补生成，单批最多 31 日；先保存文件和记录，再推进游标，返回是否还有积压。 */
    suspend fun generate(today: LocalDate = DailyCsv.today()): Boolean = mutex.withLock {
        var config = configStore.read()
        if (!config.enabled) return@withLock false
        config.validate()
        var day = LocalDate.parse(config.nextDate)
        repeat(31) {
            if (!day.isBefore(today)) return@withLock false
            val id = "daily-$day"
            if (dao.get(id) == null) {
                val (start, end) = DailyCsv.bounds(day)
                val text = DailyCsv.render(day, ledger.reportRows(start, end))
                writeSnapshot(id, text)
                dao.insert(DailyReport(id, day.toString(), config.recipient, createdAt = System.currentTimeMillis(), digest = sha256(text)))
            }
            day = day.plusDays(1)
            config = config.copy(nextDate = day.toString())
            configStore.write(config)
        }
        day.isBefore(today)
    }
    /** 显式排队不含账目的测试邮件，使用独立 ID；每日邮件关闭时也允许测试。 */
    suspend fun queueTest(): Unit = mutex.withLock {
        val config = configStore.read().also { it.validate() }
        require(dao.outstandingCount() < 50) { "待处理邮件过多，请先处理历史记录" }
        val id = "test-${UUID.randomUUID()}"
        val text = "\uFEFF费用统计清单,SMTP 配置测试（无账目）\r\n"
        writeSnapshot(id, text)
        dao.insert(DailyReport(id, DailyCsv.today().toString(), config.recipient, createdAt = System.currentTimeMillis(), digest = sha256(text), isTest = true))
    }
    /** 提交前失败可重试；提交后的不确定结果转 UNKNOWN；已接受记录只清理、不重发。 */
    suspend fun deliver(): Boolean = mutex.withLock {
        dao.sent().forEach { cleanupAccepted(it) }
        val config = configStore.read()
        var retry = false
        for (report in dao.outstanding(config.enabled)) {
            if (report.state == DeliveryState.SENDING) {
                dao.update(report.copy(state = DeliveryState.UNKNOWN, detail = "发送过程中中断，结果不明；请先检查邮箱"))
                continue
            }
            if (report.state != DeliveryState.PENDING || (!config.enabled && !report.isTest)) continue
            var submitted = false
            try {
                val snapshot = file(report.id)
                check(snapshot.exists() && sha256(snapshot.readText(Charsets.UTF_8)) == report.digest)
                mailer.send(config, report, snapshot) {
                    dao.update(report.copy(state = DeliveryState.SENDING, detail = "正在向邮件服务器提交"))
                    submitted = true
                }
                val accepted = report.copy(state = DeliveryState.SENT, acceptedAt = System.currentTimeMillis(), detail = "邮件服务器已接收；等待清理本机文件")
                dao.update(accepted)
                cleanupAccepted(accepted)
            } catch (e: CancellationException) {
                throw e // 已持久化的 SENDING 在下次执行时恢复为 UNKNOWN。
            } catch (_: Exception) {
                // 仅清理失败时绝不能回退已持久化的 SENT，否则可能重复发信。
                if (dao.get(report.id)?.state == DeliveryState.SENT) { retry = true; continue }
                val exists = file(report.id).exists() && runCatching { sha256(file(report.id).readText()) == report.digest }.getOrDefault(false)
                val uncertain = submitted || !exists
                dao.update(report.copy(state = if (uncertain) DeliveryState.UNKNOWN else DeliveryState.PENDING,
                    detail = if (!exists) "日报文件缺失或校验失败，请检查后处理"
                    else if (submitted) "发送结果不明；请检查收件箱，确认后再决定是否重发"
                    else "连接或认证失败，稍后重试；请检查网络、SMTP 端口和授权码"))
                if (!uncertain) retry = true
            }
        }
        retry || dao.outstanding(config.enabled).any { it.state == DeliveryState.PENDING }
    }
    /** 用户核对邮箱后确认收到或明确重发；重发前仍要检查原快照完整性。 */
    suspend fun resolve(id: String, received: Boolean) = mutex.withLock {
        val report = requireNotNull(dao.get(id))
        require(report.state == DeliveryState.UNKNOWN)
        if (received) {
            val accepted = report.copy(state = DeliveryState.SENT, acceptedAt = System.currentTimeMillis(), detail = "用户确认已收到；等待清理本机文件")
            dao.update(accepted)
            cleanupAccepted(accepted)
        } else {
            require(file(id).exists() && sha256(file(id).readText()) == report.digest) { "文件缺失或损坏，无法重发" }
            dao.update(report.copy(state = DeliveryState.PENDING, detail = "用户确认重试（可能产生重复邮件）"))
        }
    }
    /** 先移除配置停止后续发送，再删除日报文件与记录；与生成、发送共用锁。 */
    suspend fun clear() = mutex.withLock {
        // 先停用配置再清理；生成和发送也使用同一把锁。
        configStore.clear()
        folder.listFiles()?.forEach { check(it.isFile && it.delete()) }
        dao.clear()
    }
}
