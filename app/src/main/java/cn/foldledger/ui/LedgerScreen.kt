package cn.foldledger.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import cn.foldledger.LedgerApp
import cn.foldledger.capture.*
import cn.foldledger.data.*
import cn.foldledger.domain.*
import cn.foldledger.security.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.*
import java.time.format.DateTimeFormatter

/** 账本界面按设备本地时区显示时间；日报固定北京时间，二者用途不同。 */
private fun date(epoch: Long): String = if (epoch == 0L) "暂无记录" else
    Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable
/** 自适应账本主界面，按窗口宽度切换单/双栏，导航和草稿由会话模型管理。 */
fun LedgerScreen(app: LedgerApp, session: SessionViewModel, systemRevision: Int,
    onExport: () -> Unit, onDelete: () -> Unit, onPermission: () -> Unit, onMail: () -> Unit) {
    val wide = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass != WindowWidthSizeClass.COMPACT
    val scope = rememberCoroutineScope()
    // 将滚动状态提升到分栏条件之外，窄屏进入详情后返回仍保留列表位置。
    val ledgerListState = rememberLazyListState()
    val snack = remember { SnackbarHostState() }
    val reminderPrefs by app.settings.flow.collectAsStateWithLifecycle(AppSettings())
    LaunchedEffect(reminderPrefs.remindersEnabled, reminderPrefs.inAppReminder) {
        if (!reminderPrefs.remindersEnabled || !reminderPrefs.inAppReminder) snack.currentSnackbarData?.dismiss()
    }
    val totals by remember { app.repository.dao.totals() }.collectAsStateWithLifecycle(Totals(0,0,0,0))
    var exportDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    /** 包装账目操作；取消继续传播，其他错误仅在提醒开关允许时显示 Snackbar。 */
    fun action(block: suspend () -> Unit) { scope.launch { try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { val prefs = app.settings.flow.first()
            if (prefs.remindersEnabled && prefs.inAppReminder) snack.showSnackbar("操作失败，请重试")
        }
    } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
        if (!wide) NavigationBar {
            listOf("账本", "设置").forEachIndexed { i, label -> NavigationBarItem(selected = session.tab == i,
                onClick = { session.tab = i }, icon = { Text(if (i == 0) "账" else "设") }, label = { Text(label) }) }
        }
    }, floatingActionButton = {
        if (session.tab == 0 && session.draft == null) FloatingActionButton(onClick = { session.draft = EditDraft() }) { Text("补录", Modifier.padding(12.dp)) }
    }) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (wide) NavigationRail {
                listOf("账本", "设置").forEachIndexed { i, label -> NavigationRailItem(selected = session.tab == i,
                    onClick = { session.tab = i }, icon = { Text(if (i == 0) "账" else "设") }, label = { Text(label) }) }
            }
            if (session.tab == 1) SettingsScreen(app, systemRevision, onPermission,
                { exportDialog = true }, { deleteDialog = true }, onMail)
            else Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)) {
                Text("费用统计清单", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 16.dp))
                Text("每笔都有来源，每笔都可核对", style = MaterialTheme.typography.bodyMedium)
                Card(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("累计已确认支出 · CNY")
                        Text("¥ ${Money.format(totals.expense)}", style = MaterialTheme.typography.headlineLarge)
                        Text("收入 ${Money.format(totals.income)} · 退款 ${Money.format(totals.refund)}")
                        Text("${totals.reviewCount} 笔待核对，不计入总额", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.weight(1f)) {
                    if (wide || session.selectedId == null) Column(Modifier.weight(1f)) {
                        OutlinedTextField(session.search, { session.search = it; session.page = 0 },
                            label = { Text("搜索商户或分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("ALL" to "全部", "REVIEW" to "待确认", "IGNORED" to "已忽略").forEach { (key, label) ->
                                FilterChip(selected = session.filter == key, onClick = { session.filter = key; session.page = 0 }, label = { Text(label) })
                            }
                        }
                        val rows by remember(session.filter, session.search, session.page) {
                            app.repository.dao.page(session.filter, session.search, 50, session.page * 50)
                        }.collectAsStateWithLifecycle(emptyList())
                        LazyColumn(state = ledgerListState, modifier = Modifier.weight(1f)) {
                            if (rows.isEmpty()) item {
                                Text("这里还没有账目\n可手动补录，或在设置中开启通知采集。", Modifier.padding(vertical = 32.dp))
                            }
                            items(rows, key = { it.id }) { entry ->
                                ListItem(headlineContent = { Text(entry.merchant) },
                                    supportingContent = { Text("${date(entry.occurredAt)}\n${entry.category} · ${Status.valueOf(entry.status).title}") },
                                    trailingContent = { Column { Text("¥ ${Money.format(entry.amountFen)}"); Text(Direction.valueOf(entry.direction).title) } },
                                    modifier = Modifier.clickable { session.selectedId = entry.id })
                                HorizontalDivider()
                            }
                            item { Spacer(Modifier.height(80.dp)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = session.page > 0, onClick = { session.page-- }) { Text("上一页") }
                            Text("第 ${session.page + 1} 页")
                            TextButton(enabled = rows.size == 50, onClick = { session.page++ }) { Text("下一页") }
                        }
                    }
                    if (wide || session.selectedId != null) {
                        Box(Modifier.weight(1f).fillMaxHeight().padding(start = if (wide) 16.dp else 0.dp)) {
                            session.selectedId?.let { id -> EntryDetail(app, id,
                                onBack = { session.selectedId = null },
                                onEdit = { e -> session.draft = EditDraft(e.id, Money.format(e.amountFen), e.merchant, e.category,
                                    Direction.valueOf(e.direction), Instant.ofEpochMilli(e.occurredAt).atZone(ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0).toString()) },
                                onStatus = { status -> action { app.repository.status(id, status) } })
                            } ?: Text("选择一笔账目查看来源与修订记录", Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }
    BackHandler(session.selectedId != null || session.tab != 0) { if (session.selectedId != null) session.selectedId = null else session.tab = 0 }
    session.draft?.let { draft -> Editor(draft, { session.draft = it }, { session.draft = null }) { whenSaved ->
        val epoch = LocalDateTime.parse(whenSaved.dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        app.repository.save(whenSaved.id, whenSaved.amount, whenSaved.merchant, whenSaved.category, whenSaved.direction, epoch)
        session.draft = null
    } }
    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false }, title = { Text("导出明文账本") },
        text = { Text("CSV 包含所有账目及其状态，包括待确认和已忽略账目。文件不加密，将保存到您选择的位置；选择后需再次验证身份。") },
        confirmButton = { TextButton(onClick = { exportDialog = false; onExport() }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("取消") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除全部本地数据？") },
        text = { Text("删除账目、来源记录、修订历史、邮箱配置、日报文件与发送记录，停止采集和日报发送。此操作不可撤销，已导出的文件和已发邮件需自行删除；正在发送的邮件可能已被服务器接收。") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; onDelete() }) { Text("验证并删除") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
/** 订阅账目、来源和审计记录；人工确认不覆盖原识别观察。 */
private fun EntryDetail(app: LedgerApp, id: String, onBack: () -> Unit, onEdit: (LedgerEntry) -> Unit, onStatus: (Status) -> Unit) {
    val entry by remember(id) { app.repository.dao.observe(id) }.collectAsStateWithLifecycle(null)
    val observations by remember(id) { app.repository.dao.observations(id) }.collectAsStateWithLifecycle(emptyList())
    val audits by remember(id) { app.repository.dao.audits(id) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("返回列表") }
        entry?.let { e ->
            Text(e.merchant, style = MaterialTheme.typography.headlineSmall)
            Text("¥ ${Money.format(e.amountFen)}", style = MaterialTheme.typography.headlineLarge)
            Text("${Direction.valueOf(e.direction).title} · ${Status.valueOf(e.status).title} · ${e.category}")
            Text("${date(e.occurredAt)} · ${e.channel}")
            e.possibleDuplicateOf?.let { Text("同一通知标识曾出现过，可能是更新，也可能是另一笔消费。请核对后确认是否为独立交易。关联账目：$it") }
            FlowRow {
                TextButton(onClick = { onEdit(e) }) { Text("编辑") }
                if (e.status == Status.IGNORED.name) TextButton(onClick = { onStatus(Status.NEEDS_REVIEW) }) { Text("撤销忽略") }
                else {
                    TextButton(onClick = { onStatus(Status.CONFIRMED) }) { Text("确认为独立账目") }
                    TextButton(onClick = { onStatus(Status.IGNORED) }) { Text("忽略") }
                }
            }
            HorizontalDivider()
            Text("为什么记下这笔账", style = MaterialTheme.typography.titleMedium)
            if (observations.isEmpty()) Text("您手动补录的账目。")
            observations.forEach { o ->
                Text("${Sources.names[o.sourcePackage] ?: o.sourcePackage} · ${date(o.postTime)}\n规则 ${o.ruleVersion} · 置信度 ${(o.confidence * 100).toInt()}%\n原始识别 ${Direction.valueOf(o.direction).title} ¥ ${Money.format(o.amountFen)}\n实验格式，仅进入待确认；未保存通知全文。")
            }
            HorizontalDivider()
            Text("修订记录", style = MaterialTheme.typography.titleMedium)
            audits.forEach { Text("${date(it.at)} · ${it.action}\n之前：${it.before}\n之后：${it.after}") }
            Spacer(Modifier.height(96.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
/** 编辑草稿对话框，提交前验证格式并防止重复点击，错误在当前表单展示。 */
private fun Editor(draft: EditDraft, onChange: (EditDraft) -> Unit, onDismiss: () -> Unit, onSave: suspend (EditDraft) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (draft.id == null) "补录账目" else "编辑并确认") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.amount, { onChange(draft.copy(amount = it)) }, label = { Text("金额 · 人民币") }, singleLine = true)
            OutlinedTextField(draft.merchant, { onChange(draft.copy(merchant = it)) }, label = { Text("商户 / 备注") }, singleLine = true)
            OutlinedTextField(draft.dateTime, { onChange(draft.copy(dateTime = it)) }, label = { Text("本地时间，如 2026-09-26T12:30") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Direction.entries.filter { it != Direction.UNKNOWN }.forEach { d -> FilterChip(draft.direction == d,
                    { onChange(draft.copy(direction = d)) }, label = { Text(d.title) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("餐饮", "交通", "购物", "居家", "工资", "其他").forEach { c -> FilterChip(draft.category == c,
                    { onChange(draft.copy(category = c)) }, label = { Text(c) }) }
            }
            Text("转账和还款不计入支出；退款单独汇总。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !saving, onClick = {
            scope.launch {
                saving = true
                try { requireNotNull(Money.parse(draft.amount)); require(draft.merchant.isNotBlank()); LocalDateTime.parse(draft.dateTime); onSave(draft) }
                catch (_: Exception) { error = "保存失败，请检查金额、商户和日期格式" }
                finally { saving = false }
            }
        }) { Text("保存并确认") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable
/** 集中展示采集授权、提醒开关、后台状态及隔离测试入口。 */
private fun SettingsScreen(app: LedgerApp, systemRevision: Int, onPermission: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit, onMail: () -> Unit) {
    val context = LocalContext.current
    val prefs by app.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val authorized = remember(systemRevision) { context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, PaymentListener::class.java)) }
    val remindersAllowed = remember(systemRevision) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    /** 尝试打开系统设置；部分设备无对应入口时避免直接崩溃。 */
    fun open(intent: Intent) { runCatching { context.startActivity(intent) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置与采集状态", style = MaterialTheme.typography.headlineMedium)
        Text("本地存储，默认不上传。系统通知使用权可读取广泛通知内容；本应用只处理您选中的来源，不保存通知全文。实验解析规则尚未通过 Fold5 真实样本验收，所有识别账目需人工确认。")
        Toggle("自动采集", prefs.captureEnabled) { scope.launch { app.settings.toggle("capture", it) } }
        Sources.names.forEach { (pkg, name) -> Toggle("$name · 实验规则", pkg in prefs.sources) { scope.launch { app.settings.source(pkg, it) } } }
        Text("通知使用权：${if (authorized) "已授权" else "未授权，无法自动采集"}")
        OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("管理通知使用权") }
        HorizontalDivider()
        Text("提醒", style = MaterialTheme.typography.titleLarge)
        Toggle("所有提醒总开关", prefs.remindersEnabled) { scope.launch { app.settings.toggle("reminders", it) } }
        Toggle("记账成功提醒", prefs.successReminder) { scope.launch { app.settings.toggle("success", it) } }
        Toggle("待确认提醒", prefs.reviewReminder) { scope.launch { app.settings.toggle("review", it) } }
        Toggle("操作结果弹出提示（导出等）", prefs.inAppReminder) { scope.launch { app.settings.toggle("inApp", it) } }
        Text("提醒总开关：${if (prefs.remindersEnabled) "开启" else "关闭，所有分类均静音"}。关闭总开关后不再发送本应用通知或操作弹出提示，并清除已有提醒；账目采集和每日邮件继续运行。测试结果与发送状态仍可在页面主动查看，必要的密码验证和删除确认保留。")
        Text("系统提醒权限：${if (remindersAllowed) "可用" else "未开启"}。所有通知默认无声音、无振动，不包含金额与商户。邮件结果只在页面展示，不另发通知。")
        OutlinedButton(onClick = onPermission) { Text("允许账本提醒") }
        TextButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("系统提醒设置") }
        HorizontalDivider()
        Text("后台稳定运行", style = MaterialTheme.typography.titleLarge)
        Text("在应用信息中检查电池策略；在三星系统的后台使用限制中检查休眠名单。菜单以当前 One UI 为准。请同时确认支付 App 已开启通知并显示交易详情。")
        OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("打开应用信息与电池设置") }
        Text("最近监听连接：${date(prefs.lastConnected)}\n最近监听断连：${date(prefs.lastDisconnected)}\n最近识别：${date(prefs.lastParsed)}\n队列溢出：${prefs.droppedCount} · 处理失败：${prefs.captureErrors}")
        Text("没有新交易不代表故障。强行停止、系统休眠或通知隐藏可能导致漏记，需手动补录。", style = MaterialTheme.typography.bodySmall)
        PaymentTestPanel(app, onMail)
        HorizontalDivider()
        Text("隐私与数据", style = MaterialTheme.typography.titleLarge)
        Text("使用手机锁屏密码、PIN 或图案解锁，不使用指纹；离开应用立即锁定。导出、邮箱设置与删除需要二次认证。截图保护已开启；本地账本尚未加密，应用锁不等于数据库加密。")
        OutlinedButton(onClick = onExport) { Text("导出 CSV") }
        TextButton(onClick = onDelete) { Text("删除全部本地数据") }
        OutlinedButton(onClick = onMail) { Text("每日费用日志与邮箱设置") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
/** 统一的设置开关布局，标签占剩余宽度以适应窄屏。 */
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}
