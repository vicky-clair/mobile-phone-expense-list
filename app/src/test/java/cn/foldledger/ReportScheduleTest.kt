package cn.foldledger

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.foldledger.report.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 使用实际 WorkManager 记录验证旧计划迁移与日期唯一任务，不等待真实凌晨到来。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReportScheduleTest {
    @Test fun replacesLegacyScheduleAndKeepsOne0130TaskPerDate() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = try { WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
        val old = PeriodicWorkRequestBuilder<GenerateReportsWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(12, TimeUnit.HOURS).build()
        manager.enqueueUniquePeriodicWork("daily-reports", ExistingPeriodicWorkPolicy.KEEP, old).result.get(10, TimeUnit.SECONDS)
        val expected = ReportTiming.nextRun(Instant.now())
        ReportSchedule.ensure(context).result.get(10, TimeUnit.SECONDS)
        ReportSchedule.ensure(context).result.get(10, TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.CANCELLED, requireNotNull(manager.getWorkInfoById(old.id).get(10, TimeUnit.SECONDS)).state)
        val works = manager.getWorkInfosForUniqueWork("daily-reports-0130-${expected.toLocalDate()}").get(10, TimeUnit.SECONDS)
        assertEquals(1, works.size)
        assertEquals(WorkInfo.State.ENQUEUED, works.single().state)
        // 从 enqueue 到持久化存在毫秒级偏差，但绝不能保留旧的 00:05 目标。
        assertTrue(kotlin.math.abs(works.single().nextScheduleTimeMillis - expected.toInstant().toEpochMilli()) < 5_000)
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
    }
}
