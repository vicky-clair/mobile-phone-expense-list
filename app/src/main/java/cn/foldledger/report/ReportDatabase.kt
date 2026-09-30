package cn.foldledger.report

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 邮件提交状态；UNKNOWN 表示可能已发送，需要用户核对，禁止盲目自动重试。 */
object DeliveryState {
    // PENDING 可重试；SENDING 必须在 SMTP DATA 前落盘，恢复时转 UNKNOWN。
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val UNKNOWN = "UNKNOWN"
    // SENT 表示服务器接收或用户确认收到，不保证收件箱投递；文件清理另看 cleaned。
    const val SENT = "SENT"
}

@Entity(tableName = "daily_reports")
/** 日报发送元数据，不保存 CSV 正文；digest 校验文件，cleaned 标记本机文件已清理。 */
data class DailyReport(
    @PrimaryKey val id: String,
    val day: String, val recipient: String,
    val state: String = DeliveryState.PENDING,
    val createdAt: Long, val acceptedAt: Long = 0,
    val detail: String = "等待发送", val digest: String,
    val isTest: Boolean = false, val cleaned: Boolean = false
)

@Dao
/** 日报记录访问接口；历史页面限 50 条，后台按小批次处理待发邮件。 */
interface ReportDao {
    /** 按生成时间展示最近 50 条记录，通过 Flow 更新页面。 */
    @Query("SELECT * FROM daily_reports ORDER BY createdAt DESC LIMIT 50") fun history(): Flow<List<DailyReport>>
    /** 按唯一报告 ID 查找，支持生成幂等和发送状态恢复。 */
    @Query("SELECT * FROM daily_reports WHERE id = :id") suspend fun get(id: String): DailyReport?
    @Query("SELECT * FROM daily_reports WHERE state = 'SENDING' OR (state = 'PENDING' AND (:includeDaily OR isTest = 1) AND (isTest = 1 OR day < :dueBefore)) ORDER BY createdAt LIMIT 5")
    /** 每批最多 5 条；暂停日报后仍允许处理用户主动发起的测试邮件及中断状态。 */
    suspend fun outstanding(includeDaily: Boolean = true, dueBefore: String = "9999-12-31"): List<DailyReport>
    /** 读取已被接受但尚未清理文件的记录，恢复清理时不能再次发送。 */
    @Query("SELECT * FROM daily_reports WHERE state = 'SENT' AND cleaned = 0") suspend fun sent(): List<DailyReport>
    /** 统计所有未完成状态，限制收件地址变更和测试邮件堆积。 */
    @Query("SELECT COUNT(*) FROM daily_reports WHERE state != 'SENT'") suspend fun outstandingCount(): Int
    /** 插入唯一报告 ID，重复生成必须复用原快照而非覆盖。 */
    @Insert suspend fun insert(report: DailyReport)
    /** 持久化发送状态与清理结果，更新成功后再进入后续步骤。 */
    @Update suspend fun update(report: DailyReport)
    /** 删除发送记录；必须由服务先停用配置并清理对应文件。 */
    @Query("DELETE FROM daily_reports") suspend fun clear()
}

@Database(entities = [DailyReport::class], version = 1, exportSchema = true)
/** 独立日报 Room 数据库版本 1，不改变已有主账本数据库结构。 提供日报记录 DAO。 */
abstract class ReportDatabase : RoomDatabase() { abstract fun reports(): ReportDao }
