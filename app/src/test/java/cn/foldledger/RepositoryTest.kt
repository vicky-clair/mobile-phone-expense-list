package cn.foldledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.foldledger.capture.Sources
import cn.foldledger.data.*
import cn.foldledger.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
/** 使用 Robolectric 与内存 Room 验证真实仓库行为，不依赖真机或真实支付。 */
class RepositoryTest {
    /** 模拟测试不能污染账本，银行模拟包名也不能绕过生产白名单。 */
    @Test fun simulatedPaymentTestsNeverEnterTheLedger() = runTest {
        cn.foldledger.capture.PaymentTestLab.runSuite()
        val bank = cn.foldledger.capture.PaymentTestLab.fixture(cn.foldledger.capture.PaymentTestLab.BANK, cn.foldledger.capture.TestScenario.RECEIVE, "23.45")
        assertNull(repo.ingest(bank))
        assertTrue(repo.dao.exportPage(100, 0).isEmpty())
    }
    private lateinit var db: LedgerDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: LedgerRepository
    /** 为每个测试建立内存数据库并重置采集配置，避免用例共享业务状态。 */
    @Before fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(context)
        settings.reset(); settings.toggle("capture", true); settings.source(Sources.WECHAT, true)
        repo = LedgerRepository(db, settings)
    }
    /** 关闭测试数据库，释放 Room 资源。 */
    @After fun cleanup() { db.close() }
    /** 生成稳定的合成微信事件，可改变通知键、金额和时间测试幂等边界。 */
    private fun event(key: String = "first", amount: String = "12.30", at: Long = 1_700_000_000_000) =
        NotificationPayload(Sources.WECHAT, key, "微信支付", "支付成功：￥$amount", at)

    /** 并发投递同一事件只保留一笔，待核对不计确认支出。 */
    @Test fun concurrentReplayIsIdempotentAndReviewExcluded() = runTest {
        (1..20).map { async { repo.ingest(event()) } }.awaitAll()
        assertEquals(1, repo.dao.exportPage(100, 0).size)
        val totals = repo.dao.totals().first()
        assertEquals(0L, totals.expense)
        assertEquals(1, totals.reviewCount)
    }
    /** 金额相同但事件键不同的连续消费不能被自动合并。 */
    @Test fun equalAmountDifferentEventsRemainIndependent() = runTest {
        repo.ingest(event("one")); repo.ingest(event("two"))
        assertEquals(2, repo.dao.exportPage(100, 0).size)
    }
    /** 来源复用通知键时，新时间或新内容应进入核对，不能吞掉第二笔消费。 */
    @Test fun reusedNotificationKeyIsNeverSilentlyMerged() = runTest {
        val original = requireNotNull(repo.ingest(event(at = 179_999)))
        val reused = requireNotNull(repo.ingest(event(at = 180_001)))
        assertEquals(Status.POSSIBLE_DUPLICATE.name, reused.status)
        assertEquals(original.id, reused.possibleDuplicateOf)
        assertEquals(2, repo.dao.exportPage(100, 0).size)
        val updated = requireNotNull(repo.ingest(event(amount = "14.00", at = 180_002)))
        assertEquals(Status.POSSIBLE_DUPLICATE.name, updated.status)
        assertEquals(reused.id, updated.possibleDuplicateOf)
    }
    /** 人工修订保留原观察与审计；旧通知重放不能覆盖修订，忽略可撤销。 */
    @Test fun correctionsPreserveObservationAndIgnoreCanBeUndone() = runTest {
        val entry = requireNotNull(repo.ingest(event()))
        repo.save(entry.id, "20.00", "午餐", "餐饮", Direction.EXPENSE, entry.occurredAt)
        assertNull(repo.ingest(event())) // 重放旧事件不得覆盖人工修订。
        assertEquals(2000L, repo.dao.totals().first().expense)
        assertEquals(1230L, repo.dao.observations(entry.id).first().single().amountFen)
        assertEquals(1, repo.dao.audits(entry.id).first().size)
        repo.status(entry.id, Status.IGNORED)
        assertEquals(0L, repo.dao.totals().first().expense)
        repo.status(entry.id, Status.NEEDS_REVIEW)
        assertEquals(1, repo.dao.totals().first().reviewCount)
    }
    /** 验证不同资金方向独立统计，转账不增加消费金额。 */
    @Test fun transfersRefundsAndIncomeHaveSeparateAccounting() = runTest {
        repo.save(null, "100.00", "转账", "其他", Direction.TRANSFER, 1)
        repo.save(null, "100.00", "还款", "其他", Direction.REPAYMENT, 1)
        repo.save(null, "20.00", "退款", "其他", Direction.REFUND, 1)
        repo.save(null, "50.00", "工资", "工资", Direction.INCOME, 1)
        assertEquals(Totals(0,5000,2000,0), repo.dao.totals().first())
    }
    /** 验证未授权来源被拒绝，清空数据后已排队事件不能重新入账。 */
    @Test fun allowlistAndDeletionStopCapture() = runTest {
        settings.source(Sources.WECHAT, false)
        assertNull(repo.ingest(event()))
        settings.source(Sources.WECHAT, true)
        repo.ingest(event())
        repo.deleteAll()
        assertNull(repo.ingest(event("queued")))
        assertTrue(repo.dao.exportPage(100,0).isEmpty())
        assertFalse(settings.flow.first().captureEnabled)
    }
    /** 导出保留审核状态，并将恶意商户公式按文本处理。 */
    @Test fun exportedCsvRetainsStatusAndNeutralizesMerchantFormula() = runTest {
        repo.save(null, "1.00", "=HYPERLINK(\"bad\")", "其他", Direction.EXPENSE, 1)
        val writer = StringWriter()
        repo.export(writer)
        assertTrue(writer.toString().startsWith("\uFEFF"))
        assertTrue(writer.toString().contains("'=HYPERLINK"))
        assertTrue(writer.toString().contains("已确认"))
    }
}
