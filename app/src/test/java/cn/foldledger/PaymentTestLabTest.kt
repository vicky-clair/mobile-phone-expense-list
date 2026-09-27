package cn.foldledger

import cn.foldledger.capture.*
import cn.foldledger.domain.*
import org.junit.Assert.*
import org.junit.Test

/** 验证测试面板的场景矩阵与银行模拟隔离，防止模拟功能扩大生产识别范围。 */
class PaymentTestLabTest {
    /** 三来源各场景验证方向、精确金额、广告拒绝和未验证标记。 */
    @Test fun allSourcesSeparateExpenseIncomeRefundAndIgnoreAdverts() {
        for (source in PaymentTestLab.sources.keys) {
            for (scenario in TestScenario.entries) {
                val result = PaymentTestLab.parse(PaymentTestLab.fixture(source, scenario, "789.12"))
                if (scenario.direction == null) assertNull(result) else {
                    assertEquals(scenario.direction, result?.direction)
                    assertEquals(78912L, result?.amountFen)
                    assertFalse(requireNotNull(result).verified)
                }
            }
        }
        assertEquals(12, PaymentTestLab.runSuite().size)
        assertTrue(PaymentTestLab.runSuite().all { it.startsWith("通过") })
    }
    /** 银行模拟只能接受专用标识和完整格式，不能匹配真实银行来源。 */
    @Test fun bankSimulatorCannotMatchRealBankOrProductionSources() {
        val sample = PaymentTestLab.fixture(PaymentTestLab.BANK, TestScenario.PAY, "1.00")
        assertFalse(PaymentTestLab.BANK in Sources.names)
        assertTrue(Sources.parsers.all { it.parse(sample) == null })
        assertNull(PaymentTestLab.parse(sample.copy(sourcePackage = "com.real.bank")))
        assertNull(PaymentTestLab.parse(sample.copy(title = "真实银行")))
        assertNull(PaymentTestLab.parse(sample.copy(text = "付款成功：1.00元，优惠99元")))
        assertNull(PaymentTestLab.parse(sample.copy(text = "付款成功：1.001元")))
    }
    /** 非法金额应明确拒绝，不能悄悄四舍五入或改成默认值。 */
    @Test fun invalidFixtureAmountsAreRejectedInsteadOfChangingAmount() {
        for (amount in listOf("0", "-1", "1.001", "NaN", "99999999999")) {
            assertThrows(IllegalArgumentException::class.java) { PaymentTestLab.fixture(Sources.WECHAT, TestScenario.PAY, amount) }
        }
    }
}
