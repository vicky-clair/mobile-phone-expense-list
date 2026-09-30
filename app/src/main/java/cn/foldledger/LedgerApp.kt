package cn.foldledger

import android.app.Application
import androidx.room.Room
import cn.foldledger.data.*

/** 应用级依赖容器，惰性共享数据库、仓库和日报服务，确保业务互斥锁在进程内唯一。 */
class LedgerApp : Application() {
    private val reportDatabase by lazy { Room.databaseBuilder(this, cn.foldledger.report.ReportDatabase::class.java, "reports.db").build() }
    val reports by lazy { cn.foldledger.report.ReportService(cn.foldledger.report.SecureMailConfig(this), reportDatabase.reports(), database.ledgerDao(), java.io.File(noBackupFilesDir, "daily-reports")) }
    /** 注册下一次北京时间 01:30 日报任务，并补查已到期日报；任务自身检查邮件开关。 */
    override fun onCreate() {
        super.onCreate()
        cn.foldledger.report.ReportSchedule.ensure(this)
        cn.foldledger.report.ReportSchedule.catchUp(this)
    }
    val settings by lazy { SettingsStore(this) }
    val database by lazy { Room.databaseBuilder(this, LedgerDatabase::class.java, "ledger.db").build() }
    val repository by lazy { LedgerRepository(database, settings) }
}
