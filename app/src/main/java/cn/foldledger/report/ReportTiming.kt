package cn.foldledger.report

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 日报唯一时间规则：北京时间次日 01:30 到期，所有自动/补发入口共用此边界。 */
object ReportTiming {
    val sendTime: LocalTime = LocalTime.of(1, 30)

    /** 可生成及发送日期的右开边界；01:30 前昨天的日报尚未到期。 */
    fun cutoff(now: Instant = Instant.now()): LocalDate {
        val local = now.atZone(DailyCsv.zone)
        return local.toLocalDate().minusDays(if (local.toLocalTime() < sendTime) 1 else 0)
    }

    /** 每次重新对齐下一次北京时间 01:30，避免按实际执行时间加 24 小时而逐日漂移。 */
    fun nextRun(now: Instant = Instant.now()): ZonedDateTime {
        val local = now.atZone(DailyCsv.zone)
        val today = local.toLocalDate().atTime(sendTime).atZone(DailyCsv.zone)
        return if (today.toInstant() > now) today else today.plusDays(1)
    }
}
