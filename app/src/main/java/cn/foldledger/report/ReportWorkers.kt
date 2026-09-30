package cn.foldledger.report

import android.content.Context
import androidx.work.*
import cn.foldledger.LedgerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** WorkManager 调度入口；每天按日期注册唯一任务，生成与有网发送分离。 */
object ReportSchedule {
    /** 安排下一次 01:30，并停用旧版 00:05 周期计划；系统休眠时仍可能延后。 */
    fun ensure(context: Context): Operation {
        val now = Instant.now()
        val next = ReportTiming.nextRun(now)
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("daily-reports")
        val work = OneTimeWorkRequestBuilder<GenerateReportsWorker>()
            .setInitialDelay(Duration.between(now, next.toInstant()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        return manager.enqueueUniqueWork("daily-reports-0130-${next.toLocalDate()}", ExistingWorkPolicy.KEEP, work)
    }
    /** 应用启动或用户操作时安排一次补生成检查，已有检查运行时不重复添加。 */
    fun catchUp(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("report-catch-up", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GenerateReportsWorker>().build())
    }
    /** 有网络时串行执行发送工作；失败使用指数退避，追加任务不打断正在发送的邮件。 */
    fun send(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("report-delivery", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DeliverReportsWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
    }
}

/** 无网络约束的生成任务，按持久游标补齐日期后安排发送。 */
class GenerateReportsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /** 在 IO 调度器运行；取消必须向上传播，其他可恢复错误交给 WorkManager 退避重试。 */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // 先持久安排下一天；当前任务即便生成失败，也不会中断后续每天的计划。
            // 此处已在 IO 线程，等待 WorkManager 落库不会阻塞手机主线程。
            ReportSchedule.ensure(applicationContext).result.get()
            val more = (applicationContext as LedgerApp).reports.generate()
            ReportSchedule.send(applicationContext)
            if (more) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
/** 有网络约束的发送任务；重试仅由服务判断，UNKNOWN 不自动重发。 */
class DeliverReportsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /** 在 IO 调度器运行；取消必须向上传播，其他可恢复错误交给 WorkManager 退避重试。 */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if ((applicationContext as LedgerApp).reports.deliver()) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
