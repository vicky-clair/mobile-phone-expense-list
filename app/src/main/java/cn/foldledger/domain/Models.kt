package cn.foldledger.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/** 资金方向独立于正数金额存储；退款、转账和还款不作为普通消费支出。 */
enum class Direction(val title: String) {
    EXPENSE("支出"), INCOME("收入"), REFUND("退款"), TRANSFER("转账"), REPAYMENT("还款"), UNKNOWN("未知")
}
/** 账目审核状态；仅已确认和已修正计入正式金额汇总。 */
enum class Status(val title: String) {
    CONFIRMED("已确认"), NEEDS_REVIEW("待确认"), POSSIBLE_DUPLICATE("可能重复"), IGNORED("已忽略"), CORRECTED("已修正")
}
/** 瞬时通知输入，使用系统通知时间；标题和正文只用于解析，不长期保存全文。 */
data class NotificationPayload(val sourcePackage: String, val eventKey: String, val title: String, val text: String, val postTime: Long)
/** 来源解析候选；verified 默认关闭，合成样本不能证明真实格式可靠。 */
data class TransactionCandidate(
    val direction: Direction, val amountFen: Long, val currency: String = "CNY",
    val merchant: String = "待补充商户", val channel: String, val occurredAt: Long,
    val confidence: Double, val sourcePackage: String, val sourceEventKey: String,
    val ruleVersion: String, val verified: Boolean = false
)
/** 金额统一使用 Long 整数分，输入与显示转换采用 BigDecimal，避免浮点误差。 */
object Money {
    const val MAX_FEN = 100_000_000_00L
    /** 严格校验正数、最多两位小数及金额上限；解析失败返回 null。 */
    fun parse(text: String): Long? = runCatching {
        require(Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(text))
        BigDecimal(text).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
            .also { require(it in 1..MAX_FEN) }
    }.getOrNull()
    /** 将整数分转为两位小数字符串，不使用科学计数法或地区分组符。 */
    fun format(fen: Long): String = BigDecimal.valueOf(fen, 2).toPlainString()
}
/** 生成事件及文件内容摘要；摘要用于去重和完整性检查，不是数据加密。 */
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** 所有 CSV 出口共用的转义工具，防止分隔符破坏字段和电子表格公式注入。 */
object Csv {
    /** 为单元格添加双引号，并将危险前缀转为普通文本。 */
    fun cell(value: String): String {
        // 单纯加 CSV 双引号不能防止电子表格执行公式。
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r', '\n') ||
            value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    /** 逐字段转义并使用 CRLF 换行，字符编码及 BOM 由调用方处理。 */
    fun row(vararg values: String) = values.joinToString(",") { cell(it) } + "\r\n"
}
