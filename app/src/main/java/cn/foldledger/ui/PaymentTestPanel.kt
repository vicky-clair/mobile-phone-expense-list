package cn.foldledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.foldledger.LedgerApp
import cn.foldledger.capture.*
import cn.foldledger.data.LedgerEntry
import cn.foldledger.domain.*
import kotlinx.coroutines.launch

@Composable
/** 只读模拟测试面板，不调用账本保存或真实支付；提醒测试仍遵循全部开关。 */
fun PaymentTestPanel(app: LedgerApp, onMail: () -> Unit) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(Sources.WECHAT) }
    var scenario by remember { mutableStateOf(TestScenario.PAY) }
    var amount by remember { mutableStateOf("12.30") }
    var title by remember { mutableStateOf("微信支付") }
    var body by remember { mutableStateOf("支付成功：￥12.30") }
    var result by remember { mutableStateOf("") }
    /** 根据来源、场景和金额加载可编辑示例；非法金额直接在页面说明。 */
    fun load(nextSource: String = source, nextScenario: TestScenario = scenario) {
        source = nextSource; scenario = nextScenario
        try {
            val sample = PaymentTestLab.fixture(source, scenario, amount)
            title = sample.title; body = sample.text; result = "已加载模拟通知，可修改正文后测试"
        } catch (e: IllegalArgumentException) { result = e.message ?: "测试金额错误" }
    }
    Text("支付与收款测试", style = MaterialTheme.typography.titleLarge)
    Text("模拟微信、支付宝、银行的付款、收款与退款，不发起真实交易、不写入账本，也不加入日报。银行仅验证模拟格式，不代表已支持真实银行通知。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaymentTestLab.sources.forEach { (key, label) -> FilterChip(source == key, onClick = { load(nextSource = key) }, label = { Text(label) }) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TestScenario.entries.forEach { item -> FilterChip(scenario == item, onClick = { load(nextScenario = item) }, label = { Text(item.label) }) }
    }
    OutlinedTextField(amount, { amount = it.take(16) }, label = { Text("模拟金额（修改后点击加载示例）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { load() }) { Text("加载模拟通知") }
    OutlinedTextField(title, { title = it.take(200); result = "" }, label = { Text("通知标题") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(body, { body = it.take(2000); result = "" }, label = { Text("通知正文（可粘贴脱敏样本）") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        result = PaymentTestLab.parse(NotificationPayload(source, "local-test", title, body, System.currentTimeMillis()))?.let {
            "识别结果：${it.direction.title} ¥ ${Money.format(it.amountFen)}\n规则：${it.ruleVersion}\n按当前实验规则应进入待确认，不计入已确认总额。本次未入账。"
        } ?: "未识别为交易，不会入账。非交易提醒应得到此结果；真实支付不匹配时请手工补录。"
    }) { Text("运行本次识别测试") }
    OutlinedButton(onClick = { result = PaymentTestLab.runSuite().joinToString("\n") + "\n全部为本地模拟测试，未写入账本。" }) { Text("一键测试全部来源与场景（12 项）") }
    Text("提醒测试遵循提醒总开关、对应分类开关及系统权限；关闭时不会发通知。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "测试记账成功提醒", true to "测试待确认提醒").forEach { (review, label) ->
            OutlinedButton(onClick = { scope.launch {
                val entry = LedgerEntry("notification-test", 1230, direction = Direction.EXPENSE.name, merchant = "模拟测试", channel = "模拟",
                    occurredAt = System.currentTimeMillis(), status = if (review) Status.NEEDS_REVIEW.name else Status.CONFIRMED.name)
                result = if (Reminders.show(app, entry, app.settings)) "已提交测试提醒；未写入账本。提醒不显示金额或商户。"
                    else "未发送提醒：提醒总开关、分类开关或系统通知权限已关闭。"
            } }) { Text(label) }
        }
    }
    if (result.isNotBlank()) Text(result)
    HorizontalDivider()
    Text("邮件发送测试", style = MaterialTheme.typography.titleLarge)
    Text("验证锁屏密码后，在邮箱页面保存 SMTP 配置并点击发送测试邮件。测试邮件不含账目，可在发送记录查看结果；提醒关闭不会阻止你主动发送测试邮件。")
    OutlinedButton(onClick = onMail) { Text("打开邮箱设置与发送测试") }
}
