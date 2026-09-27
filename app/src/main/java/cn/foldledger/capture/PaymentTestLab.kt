package cn.foldledger.capture

import cn.foldledger.domain.*

/** 测试场景及期望方向；非交易提醒的期望结果为 null。 */
enum class TestScenario(val label: String, val direction: Direction?) {
    PAY("付款 / 支出", Direction.EXPENSE), RECEIVE("收款 / 收入", Direction.INCOME),
    REFUND("退款", Direction.REFUND), ADVERT("非交易提醒", null)
}


/** 隔离的测试工具，银行规则只在本对象中使用，不向生产监听注册。 */
object PaymentTestLab {
    const val BANK = "cn.foldledger.test.bank"
    val sources = linkedMapOf(Sources.WECHAT to "微信", Sources.ALIPAY to "支付宝", BANK to "银行（模拟）")
    /** 生成合成通知；先校验金额，再按来源和场景生成固定格式，不发送真实支付请求。 */
    fun fixture(source: String, scenario: TestScenario, amount: String): NotificationPayload {
        require(source in sources) { "请选择测试来源" }
        val money = Money.format(requireNotNull(Money.parse(amount)) { "请输入有效测试金额，最多两位小数" })
        val verb = when (scenario) {
            TestScenario.PAY -> if (source == Sources.WECHAT) "支付成功" else "付款成功"
            TestScenario.RECEIVE -> "收款成功"
            TestScenario.REFUND -> "退款成功"
            TestScenario.ADVERT -> "限时优惠"
        }
        val title = when (source) { Sources.WECHAT -> "微信支付"; Sources.ALIPAY -> "支付宝"; else -> "测试银行" }
        val body = when {
            scenario == TestScenario.ADVERT -> "限时优惠券${money}元，立即领取"
            source == Sources.WECHAT -> "$verb：￥$money"
            else -> "$verb：${money}元"
        }
        return NotificationPayload(source, "isolated-test", title, body, System.currentTimeMillis())
    }
    /** 微信/支付宝复用现有解析器；银行只接受专用模拟包名与标题，不能冒充真实银行支持。 */
    fun parse(input: NotificationPayload): TransactionCandidate? {
        if (input.sourcePackage != BANK) return Sources.parsers.firstOrNull { it.sourceId == input.sourcePackage }?.parse(input)
        if (input.title != "测试银行") return null
        val match = Regex("^(付款成功|收款成功|退款成功)[：:]([0-9]+(?:\\.[0-9]{1,2})?)元$").matchEntire(input.text.trim()) ?: return null
        val direction = when (match.groupValues[1]) { "付款成功" -> Direction.EXPENSE; "收款成功" -> Direction.INCOME; else -> Direction.REFUND }
        return TransactionCandidate(direction, Money.parse(match.groupValues[2]) ?: return null, channel = "银行模拟",
            occurredAt = input.postTime, sourcePackage = BANK, sourceEventKey = input.eventKey,
            ruleVersion = "bank-simulation-only-1", confidence = 0.6)
    }
    /** 执行三个来源乘四个场景的 12 项识别检查，不写数据库、不触发支付或邮件。 */
    fun runSuite(): List<String> = sources.flatMap { (source, label) ->
        TestScenario.entries.map { scenario ->
            val result = parse(fixture(source, scenario, "12.30"))
            val passed = if (scenario.direction == null) result == null else
                result?.direction == scenario.direction && result.amountFen == 1230L && !result.verified
            "${if (passed) "通过" else "失败"} · $label · ${scenario.label}"
        }
    }
}
