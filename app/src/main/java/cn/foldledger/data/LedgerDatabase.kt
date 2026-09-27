package cn.foldledger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
/** 标准账目实体：金额单位为分，revision 记录人工修订次数，possibleDuplicateOf 仅表示待核对关联。 */
data class LedgerEntry(
    @PrimaryKey val id: String,
    val amountFen: Long, val currency: String = "CNY", val direction: String,
    val merchant: String, val category: String = "其他", val channel: String,
    val occurredAt: Long, val status: String, val possibleDuplicateOf: String? = null,
    val revision: Int = 0
)
@Entity(tableName = "observations", indices = [Index(value = ["fingerprint"], unique = true), Index("transactionId"), Index("eventKeyHash")])
/** 来源观察实体；fingerprint 唯一索引防止完全相同的通知重复入库，保留结构化字段而非通知全文。 */
data class Observation(
    @PrimaryKey val id: String, val transactionId: String, val fingerprint: String,
    val eventKeyHash: String, val sourcePackage: String, val postTime: Long,
    val ruleVersion: String, val amountFen: Long, val direction: String, val confidence: Double
)
@Entity(tableName = "audit_events", indices = [Index("transactionId")])
/** 人工修订审计记录，保存操作时间和前后摘要，原始来源观察保持不变。 */
data class AuditEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String, val at: Long, val action: String, val before: String, val after: String)
/** SQL 汇总投影；三个金额单位均为分，待核对数量独立于已确认金额。 */
data class Totals(val expense: Long, val income: Long, val refund: Long, val reviewCount: Int)

@Dao
/** 主账本数据访问接口；多表写入事务及并发串行化由 LedgerRepository 负责。 */
interface LedgerDao {
    @Query("SELECT * FROM transactions WHERE occurredAt >= :start AND occurredAt < :end AND status != 'IGNORED' ORDER BY occurredAt, id")
    /** 按左闭右开时间区间读取日报账目，排除已忽略项，保留待核对项单独展示。 */
    suspend fun reportRows(start: Long, end: Long): List<LedgerEntry>
    @Query("SELECT * FROM transactions WHERE (:filter = 'ALL' OR (:filter = 'REVIEW' AND status IN ('NEEDS_REVIEW','POSSIBLE_DUPLICATE')) OR (:filter = 'IGNORED' AND status = 'IGNORED')) AND (:filter = 'IGNORED' OR status != 'IGNORED') AND (merchant LIKE '%' || :search || '%' OR category LIKE '%' || :search || '%') ORDER BY occurredAt DESC, id DESC LIMIT :limit OFFSET :offset")
    /** 按筛选条件和商户/分类关键字分页观察；稳定的时间、ID 排序避免同时间账目顺序漂移。 */
    fun page(filter: String, search: String, limit: Int, offset: Int): Flow<List<LedgerEntry>>
    @Query("SELECT * FROM transactions WHERE id = :id")
    /** 读取单笔当前账目，供修订事务检查原状态。 */
    suspend fun get(id: String): LedgerEntry?
    @Query("SELECT * FROM transactions WHERE id = :id")
    /** 订阅单笔账目变化，更新详情页。 */
    fun observe(id: String): Flow<LedgerEntry?>
    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'EXPENSE' AND status IN ('CONFIRMED','CORRECTED') AND currency = 'CNY' THEN amountFen ELSE 0 END),0) AS expense, COALESCE(SUM(CASE WHEN direction = 'INCOME' AND status IN ('CONFIRMED','CORRECTED') AND currency = 'CNY' THEN amountFen ELSE 0 END),0) AS income, COALESCE(SUM(CASE WHEN direction = 'REFUND' AND status IN ('CONFIRMED','CORRECTED') AND currency = 'CNY' THEN amountFen ELSE 0 END),0) AS refund, COALESCE(SUM(CASE WHEN status IN ('NEEDS_REVIEW','POSSIBLE_DUPLICATE') THEN 1 ELSE 0 END),0) AS reviewCount FROM transactions")
    /** 数据库直接聚合人民币的已确认支出、收入、退款，待核对只计数量。 */
    fun totals(): Flow<Totals>
    @Query("SELECT * FROM observations WHERE fingerprint = :fingerprint LIMIT 1")
    /** 查找完全相同的来源事件，用于幂等处理。 */
    suspend fun byFingerprint(fingerprint: String): Observation?
    @Query("SELECT * FROM observations WHERE eventKeyHash = :key AND postTime BETWEEN :start AND :end ORDER BY postTime DESC LIMIT 1")
    /** 在指定时间范围查找同一通知键的最近观察，仅用于标记可能重复。 */
    suspend fun recentEvent(key: String, start: Long, end: Long): Observation?
    @Query("SELECT * FROM observations WHERE transactionId = :id ORDER BY postTime")
    /** 订阅该账目的全部来源观察，展示识别依据。 */
    fun observations(id: String): Flow<List<Observation>>
    @Query("SELECT * FROM audit_events WHERE transactionId = :id ORDER BY at DESC")
    /** 按时间倒序订阅人工修订历史。 */
    fun audits(id: String): Flow<List<AuditEvent>>
    /** 插入实体；主键或观察指纹冲突交由 Room 约束报错，不静默覆盖原记录。 */
    @Insert suspend fun insert(entry: LedgerEntry)
    /** 插入实体；主键或观察指纹冲突交由 Room 约束报错，不静默覆盖原记录。 */
    @Insert suspend fun insert(observation: Observation)
    /** 插入实体；主键或观察指纹冲突交由 Room 约束报错，不静默覆盖原记录。 */
    @Insert suspend fun insert(audit: AuditEvent)
    /** 更新人工确认后的账目，调用方同步写入审计记录。 */
    @Update suspend fun update(entry: LedgerEntry)
    @Query("SELECT * FROM transactions ORDER BY occurredAt, id LIMIT :limit OFFSET :offset")
    /** 按时间和 ID 分页导出所有状态；与日报排除已忽略项的规则不同。 */
    suspend fun exportPage(limit: Int, offset: Int): List<LedgerEntry>
    /** 删除来源观察；由仓库在清空账本的事务中调用。 */
    @Query("DELETE FROM observations") suspend fun deleteObservations()
    /** 删除审计历史；由仓库在清空账本的事务中调用。 */
    @Query("DELETE FROM audit_events") suspend fun deleteAudits()
    /** 删除标准账目；调用前先停用采集并清理关联数据。 */
    @Query("DELETE FROM transactions") suspend fun deleteEntries()
}

@Database(entities = [LedgerEntry::class, Observation::class, AuditEvent::class], version = 1, exportSchema = true)
/** 主账本 Room 数据库，版本 1；新增结构必须提供迁移，不能破坏性重建用户账本。 提供账本 DAO，供仓库及日报只读查询使用。 */
abstract class LedgerDatabase : RoomDatabase() { abstract fun ledgerDao(): LedgerDao }
