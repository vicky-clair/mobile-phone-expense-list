package cn.foldledger.report

import cn.foldledger.data.LedgerEntry
import cn.foldledger.domain.*
import java.time.*

/** 北京时间日报的日期边界与 CSV 渲染器，不依赖手机当前时区。 */
object DailyCsv {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    /** 返回当天零点（含）至次日零点（不含）的毫秒区间。 */
    fun bounds(day: LocalDate): Pair<Long, Long> = day.atStartOfDay(zone).toInstant().toEpochMilli() to
        day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    /** 根据指定时间点计算北京时间日期，可注入时间用于跨日测试。 */
    fun today(now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()
    /** 输出 UTF-8 文本所需的 BOM、确认金额汇总和明细；待核对项不计入确认汇总。 */
    fun render(day: LocalDate, rows: List<LedgerEntry>): String = buildString {
        append('\uFEFF')
        append(Csv.row("费用统计清单", day.toString(), "北京时间 00:00 至次日 00:00（不含）"))
        val confirmed = rows.filter { it.status in setOf(Status.CONFIRMED.name, Status.CORRECTED.name) && it.currency == "CNY" }
        for (direction in listOf(Direction.EXPENSE, Direction.INCOME, Direction.REFUND)) {
            append(Csv.row("已确认${direction.title}", Money.format(confirmed.filter { it.direction == direction.name }.sumOf { it.amountFen }), "CNY"))
        }
        append(Csv.row("待核对笔数（不计入已确认汇总）", rows.count { it.status in setOf(Status.NEEDS_REVIEW.name, Status.POSSIBLE_DUPLICATE.name) }.toString()))
        append(Csv.row("说明", "已忽略账目不导出；转账、还款不计支出；本文件为生成时快照，后续修改不会自动重发"))
        append(Csv.row("北京时间", "商户", "分类", "类型", "金额", "币种", "状态", "渠道"))
        rows.forEach { e -> append(Csv.row(Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDateTime().toString(),
            e.merchant, e.category, Direction.valueOf(e.direction).title, Money.format(e.amountFen), e.currency,
            Status.valueOf(e.status).title, e.channel)) }
    }
}
