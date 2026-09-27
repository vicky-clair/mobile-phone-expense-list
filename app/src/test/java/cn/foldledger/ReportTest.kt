package cn.foldledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.foldledger.data.*
import cn.foldledger.report.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
/** 使用真实内存 Room 与替身发送器验证日报状态机，不向真实邮箱发送邮件。 */
class ReportTest {
    private lateinit var ledgerDb: LedgerDatabase
    private lateinit var reportsDb: ReportDatabase
    private lateinit var folder: File
    private lateinit var config: MemoryConfig
    private val day = LocalDate.of(2026, 9, 26)
    private val id = "daily-$day"
    /** 测试用内存配置，隔离 Android Keystore 的设备依赖。 */
    private class MemoryConfig(var value: MailConfig) : MailConfigStore {
        /** 读取测试配置快照。 */
        override fun read() = value
        /** 替换内存配置，模拟成功持久化。 */
        override fun write(config: MailConfig) { value = config }
        /** 恢复未配置状态，模拟清除邮箱。 */
        override fun clear() { value = MailConfig() }
    }
    /** 为每个用例建立独立账本、报告库与临时目录，使用示例域名避免真实发信。 */
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ledgerDb = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).allowMainThreadQueries().build()
        reportsDb = Room.inMemoryDatabaseBuilder(context, ReportDatabase::class.java).allowMainThreadQueries().build()
        folder = File(context.cacheDir, "report-test-${UUID.randomUUID()}")
        config = MemoryConfig(MailConfig(true, "smtp.example.com", 465, false, "sender@example.com", "code", "sender@example.com", "receiver@example.com", day.toString()))
    }
    /** 仅清理本用例创建的临时目录并关闭两份内存数据库。 */
    @After fun cleanup() { ledgerDb.close(); reportsDb.close(); folder.deleteRecursively() }
    /** 创建可注入发送行为的日报服务，默认替身只调用提交前回调。 */
    private fun service(mailer: ReportMailer = ReportMailer { _, _, _, before -> before() }) =
        ReportService(config, reportsDb.reports(), ledgerDb.ledgerDao(), folder, mailer)
    /** 直接插入可控测试账目，用于验证时间、金额和审核状态边界。 */
    private suspend fun entry(name: String, at: Long, amount: Long = 1234, status: String = "CONFIRMED", direction: String = "EXPENSE") {
        ledgerDb.ledgerDao().insert(LedgerEntry(name, amount, direction = direction, merchant = name, channel = "手动", occurredAt = at, status = status))
    }
    /** 验证北京时间半开区间、审核统计和恶意商户 CSV 转义。 */
    @Test fun dailyBoundariesTotalsAndCsvSafety() = runTest {
        val (start, end) = DailyCsv.bounds(day)
        assertEquals(86_400_000L, end - start)
        entry("previous", start - 1); entry("=FORMULA", start); entry("last", end - 1)
        entry("next", end); entry("ignored", start, status = "IGNORED")
        entry("review", start, amount = 9900, status = "NEEDS_REVIEW")
        entry("transfer", start, amount = 8800, direction = "TRANSFER")
        entry("refund", start, amount = 200, direction = "REFUND")
        val service = service()
        service.generate(day.plusDays(1))
        val csv = File(folder, "$id.csv").readText()
        assertFalse(csv.contains("previous")); assertFalse(csv.contains("next")); assertFalse(csv.contains("ignored"))
        assertTrue(csv.contains("'=FORMULA")); assertTrue(csv.contains("24.68")); assertTrue(csv.contains("99.00"))
        assertEquals(1, reportsDb.reports().history().first().size)
    }
    /** 模拟游标未持久化时中断，恢复应复用快照，后续补录不改写旧文件。 */
    @Test fun snapshotImmutableAndCursorRecoveryDoesNotDuplicate() = runTest {
        val service = service()
        service.generate(day.plusDays(1))
        val original = File(folder, "$id.csv").readText()
        entry("late", DailyCsv.bounds(day).first)
        config.value = config.value.copy(nextDate = day.toString()) // 模拟进程在游标持久化之前终止。
        service.generate(day.plusDays(1))
        assertEquals(original, File(folder, "$id.csv").readText())
        assertEquals(1, reportsDb.reports().history().first().size)
    }
    /** 确认成功后仅删除日报文件，保留账本且再次执行不会重复提交。 */
    @Test fun acceptedMailDeletesOnlyReportAndNeverResends() = runTest {
        entry("meal", DailyCsv.bounds(day).first)
        var count = 0
        val service = service(ReportMailer { _, report, file, before ->
            assertEquals("receiver@example.com", report.recipient)
            assertTrue(file.exists()); before(); count++
        })
        service.generate(day.plusDays(1)); assertFalse(service.deliver()); service.deliver()
        assertEquals(1, count)
        assertFalse(File(folder, "$id.csv").exists())
        assertEquals(DeliveryState.SENT, reportsDb.reports().get(id)?.state)
        assertEquals(1, ledgerDb.ledgerDao().exportPage(100, 0).size)
    }
    /** 提交前连接失败保留待发文件，网络恢复后可安全重试。 */
    @Test fun connectionFailureRetainsFileAndCanRetry() = runTest {
        val service = service(ReportMailer { _, _, _, _ -> throw java.io.IOException("offline") })
        service.generate(day.plusDays(1)); assertTrue(service.deliver())
        assertTrue(File(folder, "$id.csv").exists())
        assertEquals(DeliveryState.PENDING, reportsDb.reports().get(id)?.state)
        assertFalse(service().deliver())
        assertFalse(File(folder, "$id.csv").exists())
    }
    /** 提交后响应丢失转为结果不明，用户确认收到后才清理文件。 */
    @Test fun responseLossStopsAutomaticRetryUntilUserResolves() = runTest {
        var count = 0
        val service = service(ReportMailer { _, _, _, before -> before(); count++; throw java.io.IOException("response lost") })
        service.generate(day.plusDays(1)); assertFalse(service.deliver()); service.deliver()
        assertEquals(1, count)
        assertEquals(DeliveryState.UNKNOWN, reportsDb.reports().get(id)?.state)
        assertTrue(File(folder, "$id.csv").exists())
        service.resolve(id, true)
        assertFalse(File(folder, "$id.csv").exists())
    }
    /** 模拟进程停在 SENDING，恢复必须先由用户决定是否重发。 */
    @Test fun interruptedSendingRecoversAsUnknownAndExplicitRetryWorks() = runTest {
        val service = service()
        service.generate(day.plusDays(1))
        reportsDb.reports().update(requireNotNull(reportsDb.reports().get(id)).copy(state = DeliveryState.SENDING))
        service.deliver()
        assertEquals(DeliveryState.UNKNOWN, reportsDb.reports().get(id)?.state)
        service.resolve(id, false); service.deliver()
        assertEquals(DeliveryState.SENT, reportsDb.reports().get(id)?.state)
    }
    /** 已接受但未清理时，恢复只执行文件清理，不能再次调用发送器。 */
    @Test fun sentCleanupIsRecoveredWithoutSendingAgain() = runTest {
        val service = service(ReportMailer { _, _, _, _ -> fail("must not send") })
        service.generate(day.plusDays(1))
        reportsDb.reports().update(requireNotNull(reportsDb.reports().get(id)).copy(state = DeliveryState.SENT))
        service.deliver()
        assertFalse(File(folder, "$id.csv").exists())
    }
    /** 日报暂停不影响用户主动测试，清除邮件数据不能删除原账本。 */
    @Test fun pauseStopsDailyMailButAllowsExplicitTestAndClearPreservesLedger() = runTest {
        entry("meal", DailyCsv.bounds(day).first)
        val service = service()
        service.generate(day.plusDays(1))
        service.save(config.value.copy(enabled = false))
        assertFalse(service.deliver()); assertTrue(File(folder, "$id.csv").exists())
        service.queueTest(); service.deliver()
        assertEquals(DeliveryState.SENT, reportsDb.reports().history().first().first { it.isTest }.state)
        service.clear()
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertTrue(reportsDb.reports().history().first().isEmpty())
        assertFalse(config.read().enabled)
        assertEquals(1, ledgerDb.ledgerDao().exportPage(100, 0).size)
    }
    /** 未完成邮件禁止静默换收件人，快照损坏禁止发送。 */
    @Test fun pendingRecipientCannotBeSilentlyChangedAndCorruptionDoesNotSend() = runTest {
        val service = service(ReportMailer { _, _, _, _ -> fail("must not send corrupted file") })
        service.generate(day.plusDays(1))
        try { service.save(config.value.copy(recipient = "another@example.com")); fail("must block address change") }
        catch (_: IllegalArgumentException) { }
        File(folder, "$id.csv").writeText("corrupted")
        service.deliver()
        assertEquals(DeliveryState.UNKNOWN, reportsDb.reports().get(id)?.state)
    }
    /** 验证跨年逐日补生成与固定北京时间，生成游标推进到下一未完成日。 */
    @Test fun missedDaysCatchUpAcrossMonthAndIgnoreTimezoneOfDevice() = runTest {
        config.value = config.value.copy(nextDate = "2026-12-31")
        service().generate(LocalDate.of(2027, 1, 3))
        assertEquals(setOf("2026-12-31", "2027-01-01", "2027-01-02"), reportsDb.reports().history().first().map { it.day }.toSet())
        assertEquals("2027-01-03", config.read().nextDate)
        assertEquals(LocalDate.of(2027, 1, 1), DailyCsv.today(java.time.Instant.parse("2026-12-31T16:00:00Z")))
    }
}
