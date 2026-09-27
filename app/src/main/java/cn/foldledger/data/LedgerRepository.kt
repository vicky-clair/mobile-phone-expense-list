package cn.foldledger.data

import androidx.room.withTransaction
import cn.foldledger.capture.Sources
import cn.foldledger.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Writer
import java.time.Instant
import java.util.UUID

/** 账本业务入口：使用互斥锁串行化写入，用 Room 事务保证账目、观察及审计的一致性。 */
class LedgerRepository(private val db: LedgerDatabase, private val settings: SettingsStore) {
    val dao = db.ledgerDao()
    private val mutation = Mutex()
    /** 重新检查采集开关和来源，解析后去重入库；不匹配或完全重放返回 null。 */
    suspend fun ingest(payload: NotificationPayload): LedgerEntry? = mutation.withLock {
        val prefs = settings.flow.first()
        if (!prefs.captureEnabled || payload.sourcePackage !in prefs.sources) return@withLock null
        val candidate = Sources.parsers.firstOrNull { it.sourceId == payload.sourcePackage }?.parse(payload)
            ?: return@withLock null
        db.withTransaction {
            val key = sha256("${payload.sourcePackage}|${payload.eventKey}")
            // 部分支付应用会对连续多笔消费复用同一个通知键。
            // 只有包括 postTime 在内完全相同的事件才能安全忽略。
            val fingerprint = sha256("$key|${payload.title}|${payload.text}|${payload.postTime}")
            if (dao.byFingerprint(fingerprint) != null) return@withTransaction null
            val prior = dao.recentEvent(key, payload.postTime - 180_000, payload.postTime + 180_000)
            val status = when {
                prior != null -> Status.POSSIBLE_DUPLICATE
                candidate.verified && candidate.confidence >= 0.95 -> Status.CONFIRMED
                else -> Status.NEEDS_REVIEW
            }
            val entry = LedgerEntry(UUID.randomUUID().toString(), candidate.amountFen, candidate.currency,
                candidate.direction.name, candidate.merchant, channel = candidate.channel,
                occurredAt = candidate.occurredAt, status = status.name, possibleDuplicateOf = prior?.transactionId)
            dao.insert(entry)
            dao.insert(observation(candidate, entry.id, fingerprint, key))
            entry
        }.also { if (it != null) settings.time("parsed") }
    }
    /** 从候选提取必要结构化字段，保存指纹与通知键摘要，不保存通知原文。 */
    private fun observation(c: TransactionCandidate, id: String, fingerprint: String, key: String) =
        Observation(UUID.randomUUID().toString(), id, fingerprint, key, c.sourcePackage, c.occurredAt,
            c.ruleVersion, c.amountFen, c.direction.name, c.confidence)

    /** 校验并保存人工补录/修订，同时记录前后差异；人工保存视为已确认。 */
    suspend fun save(id: String?, amount: String, merchant: String, category: String, direction: Direction, occurredAt: Long) = mutation.withLock {
        val fen = requireNotNull(Money.parse(amount)) { "请输入有效金额，最多两位小数" }
        require(merchant.isNotBlank() && merchant.length <= 80) { "商户名称应为 1–80 字" }
        db.withTransaction {
            val before = id?.let { requireNotNull(dao.get(it)) }
            val next = LedgerEntry(before?.id ?: UUID.randomUUID().toString(), fen, direction = direction.name,
                merchant = merchant.trim(), category = category, channel = before?.channel ?: "手动补录",
                occurredAt = occurredAt, status = if (before == null) Status.CONFIRMED.name else Status.CORRECTED.name,
                revision = (before?.revision ?: -1) + 1)
            if (before == null) dao.insert(next) else dao.update(next)
            dao.insert(AuditEvent(transactionId = next.id, at = System.currentTimeMillis(), action = "保存并确认",
                before = before?.explain() ?: "新建", after = next.explain()))
        }
    }
    /** 只允许明确的人工审核状态转换；确认独立交易时移除可能重复关联。 */
    suspend fun status(id: String, next: Status) = mutation.withLock {
        require(next in setOf(Status.CONFIRMED, Status.IGNORED, Status.NEEDS_REVIEW))
        db.withTransaction {
            val before = requireNotNull(dao.get(id))
            val after = before.copy(status = next.name, revision = before.revision + 1,
                possibleDuplicateOf = if (next == Status.CONFIRMED) null else before.possibleDuplicateOf)
            dao.update(after)
            dao.insert(AuditEvent(transactionId = id, at = System.currentTimeMillis(), action = next.title,
                before = before.explain(), after = after.explain()))
        }
    }
    /** 在同一事务快照内每批导出 200 条全部状态记录，避免导出途中数据变动导致分页错位。 */
    suspend fun export(writer: Writer) = mutation.withLock {
        db.withTransaction {
            writer.write("\uFEFF")
            writer.write(Csv.row("时间", "商户", "分类", "类型", "金额", "币种", "状态", "渠道"))
            var offset = 0
            do {
                val rows = dao.exportPage(200, offset)
                rows.forEach { e -> writer.write(Csv.row(Instant.ofEpochMilli(e.occurredAt).toString(), e.merchant,
                    e.category, Direction.valueOf(e.direction).title, Money.format(e.amountFen), e.currency,
                    Status.valueOf(e.status).title, e.channel)) }
                offset += rows.size
            } while (rows.size == 200)
            writer.flush()
        }
    }
    /** 先重置采集设置，再在事务中清空账目及关联表，阻止已排队事件重新入账。 */
    suspend fun deleteAll() = mutation.withLock {
        settings.reset() // 先停采集，避免清空后排队事件重新写入。
        db.withTransaction { dao.deleteObservations(); dao.deleteAudits(); dao.deleteEntries() }
    }
    /** 生成审计用的可读账目摘要，保留金额、方向、状态和原发生时间。 */
    private fun LedgerEntry.explain() = "${Money.format(amountFen)} $currency · $merchant · $category · ${Direction.valueOf(direction).title} · ${Status.valueOf(status).title} · ${Instant.ofEpochMilli(occurredAt)}"
}
