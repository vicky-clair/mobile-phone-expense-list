package cn.foldledger

import cn.foldledger.capture.*
import cn.foldledger.domain.*
import org.junit.Assert.*
import org.junit.Test

/** 领域规则回归：金额精度、实验解析边界及 CSV 注入防护，无需真实通知或网络。 */
class DomainTest {
    /** 构造可覆盖来源和标题的微信合成通知，便于测试伪造输入。 */
    private fun wechat(text: String, title: String = "微信支付", pkg: String = Sources.WECHAT) =
        WeChatParser().parse(NotificationPayload(pkg, "event", title, text, 1_700_000_000_000))
    /** 验证整数分的精确转换，并拒绝零、负数、超限及不规范数字。 */
    @Test fun exactMoney() {
        assertEquals(29L, Money.parse("0.29"))
        assertEquals(123456789L, Money.parse("1234567.89"))
        assertEquals("0.01", Money.format(1))
        listOf("0", "-1", "1.001", "NaN", "1e5", "1,000", "100000001", "99999999999", " 1").forEach {
            assertNull("must reject $it", Money.parse(it))
        }
    }
    /** 验证实验规则方向和金额正确，但不会标记为经过真实样本验证。 */
    @Test fun experimentalRulesNeverConfirm() {
        val payment = requireNotNull(wechat("支付成功：￥12.30"))
        assertEquals(1230L, payment.amountFen)
        assertEquals(Direction.EXPENSE, payment.direction)
        assertFalse(payment.verified)
        assertEquals(Direction.INCOME, wechat("收款成功：￥9.00")?.direction)
        assertEquals(Direction.REFUND, wechat("退款成功：￥9.00")?.direction)
        val alipay = AlipayParser().parse(NotificationPayload(Sources.ALIPAY, "a", "支付宝", "付款成功：0.29元", 42))
        assertEquals(29L, alipay?.amountFen)
        assertEquals(42L, alipay?.occurredAt)
    }
    /** 确保营销、验证码、红包、外币和伪造来源不能被记为消费。 */
    @Test fun rejectsNonTransactionsAndSpoofedSources() {
        listOf("优惠券￥12.30", "验证码123456", "收到红包￥12.30", "转账成功：￥12.30",
            "支付成功：USD12.30", "支付成功：￥0", "支付成功：￥12.301", "今日活动 支付成功：￥12.30",
            "支付成功：￥12.30\n优惠券￥20").forEach { assertNull(it, wechat(it)) }
        assertNull(wechat("支付成功：￥12.30", pkg = "fake.app"))
        assertNull(wechat("支付成功：￥12.30", title = "好友消息"))
    }
    /** 验证引号/分隔符转义及带空白的危险公式前缀处理。 */
    @Test fun csvEscapesQuotesAndNeutralizesFormula() {
        assertEquals("\"a,\"\"b\"\"\"", Csv.cell("a,\"b\""))
        listOf("=1+1", "+SUM(A1)", "-1+2", "@cmd", "  =SUM(A1)", "\t=1", "\n=1").forEach {
            assertTrue(Csv.cell(it).startsWith("\"'"))
        }
        assertEquals("\"商户\"", Csv.cell("商户"))
    }
}
