package cn.foldledger

import cn.foldledger.report.ReportTiming
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

/** 验证北京时间 01:30 边界、跨年与延迟后重新对齐，不依赖设备本地时区。 */
class ReportTimingTest {
    @Test fun yesterdayBecomesDueOnlyAt0130() {
        assertEquals(LocalDate.of(2026, 9, 26), ReportTiming.cutoff(Instant.parse("2026-09-26T17:29:59Z")))
        assertEquals(LocalDate.of(2026, 9, 27), ReportTiming.cutoff(Instant.parse("2026-09-26T17:30:00Z")))
    }
    @Test fun nextRunUsesTodayBefore0130AndTomorrowAtOrAfterIt() {
        assertEquals(Instant.parse("2026-09-26T17:30:00Z"), ReportTiming.nextRun(Instant.parse("2026-09-26T16:20:00Z")).toInstant())
        assertEquals(Instant.parse("2026-09-27T17:30:00Z"), ReportTiming.nextRun(Instant.parse("2026-09-26T17:30:00Z")).toInstant())
        assertEquals(Instant.parse("2026-09-27T17:30:00Z"), ReportTiming.nextRun(Instant.parse("2026-09-27T03:00:00Z")).toInstant())
    }
    @Test fun yearBoundaryDoesNotFollowDeviceTimezone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(LocalDate.of(2026, 12, 31), ReportTiming.cutoff(Instant.parse("2026-12-31T17:29:59Z")))
            assertEquals(Instant.parse("2026-12-31T17:30:00Z"), ReportTiming.nextRun(Instant.parse("2026-12-31T16:00:00Z")).toInstant())
        } finally { TimeZone.setDefault(original) }
    }
}
