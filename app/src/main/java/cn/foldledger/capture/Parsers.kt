package cn.foldledger.capture

import cn.foldledger.domain.*

/** 每个真实来源使用独立解析器；无法确认格式时返回 null。 */
interface TransactionParser {
    val sourceId: String
    /** 先核对来源与标题，再严格匹配通知格式；不匹配时不产生交易候选。 */
    fun parse(input: NotificationPayload): TransactionCandidate?
}

/** 生产采集来源白名单，只注册当前微信/支付宝实验规则，不包含银行模拟。 */
object Sources {
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val WECHAT = "com.tencent.mm"
    val names = linkedMapOf(ALIPAY to "支付宝", WECHAT to "微信支付")
    val parsers: List<TransactionParser> = listOf(AlipayParser(), WeChatParser())
}

// 仅为合成样本；通过真实设备样本验证前，这些规则绝不自动确认账目。
/** 支付宝合成样本规则，只覆盖付款、收款与退款，结果必须人工确认。 */
class AlipayParser : TransactionParser {
    override val sourceId = Sources.ALIPAY
    /** 先核对来源与标题，再严格匹配通知格式；不匹配时不产生交易候选。 */
    override fun parse(input: NotificationPayload): TransactionCandidate? {
        if (input.sourcePackage != sourceId || input.title != "支付宝") return null
        return candidate(input, Regex("^(付款成功|收款成功|退款成功)[：:]([0-9]+(?:\\.[0-9]{1,2})?)元$"), "Alipay", "alipay-lab-1")
    }
}
/** 微信支付合成样本规则，严格匹配人民币格式，不将聊天、红包或营销当作交易。 */
class WeChatParser : TransactionParser {
    override val sourceId = Sources.WECHAT
    /** 先核对来源与标题，再严格匹配通知格式；不匹配时不产生交易候选。 */
    override fun parse(input: NotificationPayload): TransactionCandidate? {
        if (input.sourcePackage != sourceId || input.title != "微信支付") return null
        return candidate(input, Regex("^(支付成功|收款成功|退款成功)[：:]￥([0-9]+(?:\\.[0-9]{1,2})?)$"), "WeChatPay", "wechat-lab-1")
    }
}
/** 将整条匹配结果转换为结构化候选；校验金额并保留来源、事件键和规则版本。 */
private fun candidate(input: NotificationPayload, pattern: Regex, channel: String, version: String): TransactionCandidate? {
    val match = pattern.matchEntire(input.text.trim()) ?: return null
    val amount = Money.parse(match.groupValues[2]) ?: return null
    val direction = when (match.groupValues[1]) {
        "付款成功", "支付成功" -> Direction.EXPENSE
        "收款成功" -> Direction.INCOME
        "退款成功" -> Direction.REFUND
        else -> return null
    }
    return TransactionCandidate(direction, amount, channel = channel, occurredAt = input.postTime,
        confidence = 0.6, sourcePackage = input.sourcePackage, sourceEventKey = input.eventKey,
        ruleVersion = version)
}
