package cn.foldledger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.foldledger.LedgerApp
import cn.foldledger.report.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
/** 通过二次认证后进入的邮箱页；密钥不回显，未保存的表单不会用于发送。 */
fun MailSettingsScreen(app: LedgerApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(MailConfig()) }
    var port by remember { mutableStateOf("465") }
    var password by remember { mutableStateOf("") }
    var hasPassword by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var resend by remember { mutableStateOf<DailyReport?>(null) }
    var clear by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    val history by app.reports.history.collectAsStateWithLifecycle(emptyList())
    /** 串行执行页面操作并切换 IO 线程；取消向上传播，错误信息避免泄露授权码。 */
    fun task(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { message = e.message ?: "配置有误" }
            catch (_: Exception) { message = "操作失败，请检查配置；密钥不可用时请清除邮箱配置后重新填写" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try {
            val saved = withContext(Dispatchers.IO) { app.reports.config() }
            hasPassword = saved.password.isNotBlank()
            // 表单不回显已有授权码；保存时留空才从加密存储保留原值。
            config = saved.copy(password = "")
            port = saved.port.toString()
            ready = true
        } catch (_: Exception) { message = "无法解密邮箱配置，请清除邮箱配置后重新填写" }
    }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("返回设置") }
        Text("每日费用日志", style = MaterialTheme.typography.headlineMedium)
        Text("每天按北京时间 00:00–24:00 统计一个 CSV，次日凌晨 01:30 安排生成和发送，不提前发送。首次启用从当天开始；暂停后重新启用从当天恢复。关机、休眠或断网会延后，恢复后补发已到期日报。测试邮件可随时主动发送。")
        Text("附件包含商户、金额、分类与状态。待核对账目不计入已确认汇总；已忽略账目不导出。邮件服务器确认接收后删除本机日报文件，原账本保留。邮件副本仍会保留在邮箱服务中。")
        Row { Text("启用每日邮件", Modifier.weight(1f)); Switch(config.enabled, { config = config.copy(enabled = it) }, enabled = ready && !busy) }
        OutlinedTextField(config.host, { config = config.copy(host = it.trim()) }, enabled = ready && !busy,
            label = { Text("SMTP 主机（如 smtp.example.com）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, enabled = ready && !busy,
            label = { Text("SMTP 端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row { Text("使用 STARTTLS（通常端口 587）", Modifier.weight(1f)); Switch(config.startTls, {
            config = config.copy(startTls = it); port = if (it) "587" else "465"
        }, enabled = ready && !busy) }
        Text("关闭 STARTTLS 时使用隐式 TLS（通常 465）。始终验证服务器证书，不支持明文 SMTP。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(config.username, { config = config.copy(username = it.trim()) }, enabled = ready && !busy,
            label = { Text("SMTP 登录账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, enabled = ready && !busy,
            label = { Text(if (hasPassword) "新授权码（留空保留原值）" else "邮箱 SMTP 授权码") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(config.from, { config = config.copy(from = it.trim()) }, enabled = ready && !busy,
            label = { Text("发件邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(config.recipient, { config = config.copy(recipient = it.trim()) }, enabled = ready && !busy,
            label = { Text("收件邮箱（一个）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("账号和授权码经 Android Keystore 加密保存。保存后生效；发送使用已保存配置。有未完成邮件时不能更改收件地址。")
        Button(enabled = ready && !busy, onClick = { task {
            val savedPassword = password.ifBlank { app.reports.config().password }
            app.reports.save(config.copy(port = port.toIntOrNull() ?: 0, password = savedPassword))
            withContext(Dispatchers.Main) { hasPassword = true; password = "" }
            ReportSchedule.ensure(app); ReportSchedule.catchUp(app); ReportSchedule.send(app)
            "已保存。日报从启用当天开始，次日生成；历史待发文件会继续处理。"
        } }) { Text("保存设置") }
        Text("邮件发送测试", style = MaterialTheme.typography.titleLarge)
        Text("先保存上方配置，再发送一封不含账目的测试邮件。无需开启每日邮件；结果显示在下方记录，关闭提醒后也不会弹出通知。")
        OutlinedButton(enabled = ready && !busy, onClick = { task {
            app.reports.queueTest(); ReportSchedule.send(app); "测试邮件已排队（仅测试附件，不含账目），结果见下方发送记录"
        } }) { Text("发送测试邮件（使用已保存配置）") }
        OutlinedButton(enabled = !busy, onClick = { task {
            val text = app.reports.preview()
            withContext(Dispatchers.Main) { preview = text.take(8000) + if (text.length > 8000) "\n（预览截取前 8000 字，实际日报保留完整内容）" else "" }
            "预览昨日当前账本，不生成文件、不发送；历史已生成日报内容不会改变"
        } }) { Text("预览昨日费用日志") }
        OutlinedButton(enabled = ready && !busy, onClick = { task {
            ReportSchedule.catchUp(app); ReportSchedule.send(app); "已安排补生成及重试；结果不明的邮件需单独处理"
        } }) { Text("检查漏发并重试") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message)
        HorizontalDivider()
        Text("最近 50 条发送记录", style = MaterialTheme.typography.titleLarge)
        Text("“服务器已接收”不等于已到收件箱，请同时检查垃圾邮件。SMTP 无法保证严格只发一次，发送中断时不会盲目重试。", style = MaterialTheme.typography.bodySmall)
        if (history.isEmpty()) Text("暂无记录。首次日报会在启用的次日生成。")
        history.forEach { report ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${report.day}${if (report.isTest) " · 测试" else " · 日报"}")
                Text(report.recipient)
                Text(report.detail)
                if (report.state == DeliveryState.UNKNOWN) {
                    TextButton(enabled = !busy, onClick = { task { app.reports.resolve(report.id, true); "已记录收到并清理本机日报文件" } }) { Text("我已收到，清理文件") }
                    TextButton(enabled = !busy, onClick = { resend = report }) { Text("未收到，重新发送") }
                }
            } }
        }
        TextButton(enabled = !busy, onClick = { clear = true }) { Text("清除邮箱配置与日报文件") }
        Spacer(Modifier.height(24.dp))
    }
    preview?.let { content -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("昨日费用日志预览") },
        text = { Text(content, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }) }
    resend?.let { report -> AlertDialog(onDismissRequest = { resend = null }, title = { Text("确认重发？") },
        text = { Text("请先检查收件箱和垃圾邮件。上次可能已经发送成功，重发可能产生重复邮件。") },
        confirmButton = { TextButton(onClick = { resend = null; task { app.reports.resolve(report.id, false); ReportSchedule.send(app); "已排队重发" } }) { Text("确认重发") } },
        dismissButton = { TextButton(onClick = { resend = null }) { Text("取消") } }) }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清除邮件数据？") },
        text = { Text("停止日报邮件，删除邮箱配置、所有待发日报文件和发送记录，保留原账本。已发或正在发送的邮件无法撤回。") },
        confirmButton = { TextButton(onClick = { clear = false; task {
            app.reports.clear()
            withContext(Dispatchers.Main) { config = MailConfig(); password = ""; hasPassword = false; port = "465"; ready = true }
            "邮箱配置与日报文件已清除，原账本保留"
        } }) { Text("清除") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
