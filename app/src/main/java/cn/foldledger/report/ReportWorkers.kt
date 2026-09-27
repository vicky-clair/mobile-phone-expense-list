package cn.foldledger.report

import android.content.Context
import androidx.work.*
import cn.foldledger.LedgerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** WorkManager 调度入口；唯一名称避免重复周期任务，生成与有网发送分离。 */
object ReportSchedule {
    /** 首次安排次日约 00:05 的 24 小时周期任务，KEEP 保留已存在计划；系统可能延后执行。 */
    fun ensure(context: Context) {
        val now = ZonedDateTime.now(DailyCsv.zone)
        val next = now.toLocalDate().plusDays(1).atTime(0, 5).atZone(DailyCsv.zone)
        val work = PeriodicWorkRequestBuilder<GenerateReportsWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-reports", ExistingPeriodicWorkPolicy.KEEP, work)
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
