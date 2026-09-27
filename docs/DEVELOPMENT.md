# 费用统计清单开发文档

版本基线：0.3.0 / versionCode 3。整理日期：2026-09-27。本文描述当前代码实现，适合接手开发、排查问题和扩展功能；不将未来计划当作已交付能力。

语言入口：[English README](../README.md) · [中文 README](../README.zh-CN.md) · [日本語 README](../README.ja.md)。使用细节见[每日邮件说明](DAILY_MAIL.md)，构建证据见[验证记录](VERIFICATION.md)。

## 1. 需求与当前范围

应用在本地管理可人工核对的费用账本，通过用户授权的通知来源解析交易，按北京时间每天生成独立 CSV，使用个人 SMTP 邮箱发送。服务器接收后删除本机日报附件，保留原账本及发送记录。

后续用户需求优先于[原始 Fold5 设计书](../三星折叠屏自动记账App开发文档.md)：

| 原方案 | 当前实现 |
| --- | --- |
| 原名“折页账本” | 安装名称与主界面名称“费用统计清单”，包名仍为 `cn.foldledger` |
| 优先强生物识别，允许系统凭据回退 | 只允许系统锁屏密码、PIN、图案，不使用指纹 |
| 每周通过 HTTPS 网关发送 | 每日通过用户自行配置的 SMTP 发送 |
| 后续扩展银行规则 | 目前仅隔离的银行模拟测试，不启用真实银行通知采集 |
| 两类记账提醒 | 提醒总开关、成功/待确认分类开关、操作提示开关及提醒测试 |

已实现本地账本、修订审计、实验通知解析、事件去重、应用锁、自适应布局、提醒控制、日报及异常恢复、测试面板。未实现真实银行规则、跨平台交易自动关联、加密备份、OAuth 邮箱登录或正式发布流程。

## 2. 开发环境与运行

| 项目 | 固定配置 |
| --- | --- |
| JDK | 17；现有验证环境为 17.0.2 |
| Gradle Wrapper | 9.3.1，发行包 SHA-256 固定在 Wrapper 配置中 |
| AGP / Compose Kotlin 插件 / KSP | 9.1.1 / 2.2.10 / 2.3.9 |
| SDK / Build Tools | compileSdk 37 / 36.1.0 |
| 安装及行为基线 | minSdk 33（Android 13）/ targetSdk 36 |
| 应用标识 | `cn.foldledger`；工程内部名 `FoldLedger` |
| UI / 数据 / 后台 / 邮件 | Compose Material 3 Adaptive / Room、DataStore / WorkManager / Angus Mail |

配置 `ANDROID_HOME`，或在不提交的 `local.properties` 中填写 `sdk.dir`。Windows 项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

第二条命令需要已连接并授权调试的手机。APK 是调试签名开发包，尚未配置发布签名；升级已有安装时需保持包名和签名。临时编译参数 `-Pledger.compileSdk=36` 只改变编译 SDK，不代表完成任何平台或真机验收。

依赖下载较慢时可临时添加 `-Dorg.gradle.internal.http.connectionTimeout=15000` 与 `-Dorg.gradle.internal.http.socketTimeout=30000`。不要将邮箱授权码、签名密钥、本机 SDK 路径写进公共配置。

## 3. 目录与模块职责

```text
app/src/main/java/cn/foldledger/
  LedgerApp.kt                 应用级依赖容器及日报调度注册
  MainActivity.kt              系统认证、私密窗口、导出和邮箱入口
  domain/Models.kt             金额、方向/状态、通知候选、摘要、CSV
  data/LedgerDatabase.kt       主账本实体、DAO、SQL 汇总
  data/LedgerRepository.kt     入库、去重、修订、导出与删除事务
  data/SettingsStore.kt        DataStore 设置及提醒发布互斥锁
  capture/Parsers.kt           微信/支付宝实验规则和生产来源白名单
  capture/PaymentListener.kt   通知回调及有界后台消费
  capture/Reminders.kt         系统通知与操作 Toast 统一出口
  capture/PaymentTestLab.kt    隔离模拟场景与银行测试规则
  security/SessionViewModel.kt 界面认证状态、草稿和导航
  report/MailConfig.kt         配置校验、Keystore 加密读写
  report/DailyCsv.kt           北京时间区间及日报 CSV
  report/ReportDatabase.kt     独立发送记录与状态
  report/ReportService.kt      快照、游标、发送及清理状态机
  report/SmtpMailer.kt         TLS 连接、MIME 邮件及发送前回调
  report/ReportWorkers.kt      唯一后台任务与网络重试
  ui/LedgerScreen.kt           账本、详情、编辑与设置
  ui/MailSettingsScreen.kt     邮箱配置、测试、预览和发送记录
  ui/PaymentTestPanel.kt       识别模拟、提醒测试与邮件测试入口
app/src/test/java/cn/foldledger/  回归测试
app/schemas/                    Room schema 历史
```

`LedgerApp` 惰性创建共享仓库和服务。不要在页面或 Worker 中重新创建多个 `ReportService`/`LedgerRepository` 实例，否则各自的互斥锁无法保护同一业务数据。

主要调用路径：

```text
系统通知 → PaymentListener → LedgerRepository → 来源解析器 → Room
                                          └→ Reminders（按开关）
界面补录/核对 → LedgerRepository → 账目 + 审计
GenerateReportsWorker → ReportService → 日报文件 + 发送记录 + 游标
DeliverReportsWorker → ReportService → SmtpMailer → 状态落盘 → 文件清理
测试面板 → PaymentTestLab → 内存结果（不进入仓库）
```

## 4. 数据模型与存储

### 4.1 统一约定

- 金额是 `Long` 整数分，使用 `BigDecimal` 转换，接受正数且最多两位小数，上限一亿元；不使用 `Float`/`Double` 记账。
- 时间戳为 Unix 毫秒。账本显示和手工录入使用手机本地时间；日报日期固定使用 `Asia/Shanghai`。
- 方向包含 `EXPENSE`、`INCOME`、`REFUND`、`TRANSFER`、`REPAYMENT`、`UNKNOWN`。金额始终为正，业务含义由方向决定。
- 状态包含 `CONFIRMED`、`NEEDS_REVIEW`、`POSSIBLE_DUPLICATE`、`IGNORED`、`CORRECTED`。只有已确认/已修正人民币账目进入确认金额汇总。

### 4.2 主账本 ledger.db（Room v1）

| 表 / 实体 | 关键字段 | 用途 |
| --- | --- | --- |
| `transactions` / `LedgerEntry` | UUID 主键、整数分、币种、方向、商户、分类、渠道、发生时间、状态、可能重复关联、revision | 当前标准账目 |
| `observations` / `Observation` | UUID 主键、transactionId、唯一 fingerprint、eventKeyHash、来源包名、通知时间、规则版本、原识别金额/方向/置信度 | 可追溯来源，不保存通知全文 |
| `audit_events` / `AuditEvent` | 自增主键、transactionId、操作时间、动作、前后摘要 | 人工修订历史 |

当前表通过 ID 逻辑关联，未声明外键级联删除，仓库显式按观察、审计、账目顺序清理。`fingerprint` 唯一索引提供数据库层去重保护。

首页分页每页 50 条；汇总由 SQL 完成。手动 CSV 导出在同一事务中每批读取 200 条，包含所有状态。日报按单日区间一次读取账目，排除已忽略项；大规模账本尚未做容量和内存基准测试。

### 4.3 reports.db（独立 Room v1）

`daily_reports` 保存 `id`、日期、收件地址、状态、生成/接受时间、可读详情、CSV 摘要、测试标识和清理标识，不保存附件正文。

- 日报 ID：`daily-YYYY-MM-DD`，日期唯一，不因重新启动或收件人改变而自动生成第二份。
- 测试 ID：`test-UUID`，与正式日报分开。
- 历史页显示最近 50 条；后台每批处理最多 5 条待发或中断记录。
- `cleaned=false` 的已接受记录只执行清理，不再次提交 SMTP。

主账本 schema 和日报 schema 位于 `app/schemas/`。两库均为 v1，后续变更必须升版本并提供显式迁移；禁止通过破坏性重建解决升级问题。不要手工注释或修改生成的 JSON schema。

### 4.4 设置与文件

| 位置 | 内容 | 保护方式 |
| --- | --- | --- |
| DataStore `settings` | 采集、来源、提醒和健康计数 | 应用私有存储 |
| SharedPreferences `mail_secure` 的 `config` | 整体 SMTP 配置和 nextDate 游标 | Android Keystore AES-GCM 加密 |
| `noBackupFilesDir/daily-reports/` | 待发日报 CSV | 应用私有目录，非加密文件 |
| 用户通过文件选择器指定位置 | 手动导出 CSV | 用户自行管理，不由日报清理逻辑删除 |

SMTP 配置使用 Keystore 别名 `ledger-mail-v1`。存储格式为 Base64 编码的随机 12 字节 IV 与带认证标签的密文；每次写入重新生成 IV。密钥不要求 UI 每次认证，因此锁屏状态下可后台发信。密钥不可用时页面要求清除邮件配置并重填，不降级为明文。

## 5. 通知采集、解析与去重

1. `PaymentListener` 订阅采集开关和来源设置，维护跨线程可见的来源集合。
2. 回调先检查用户来源集合和生产白名单，丢弃组汇总通知。仅截取标题最多 200 字、正文最多 2000 字，优先使用扩展文本。
3. 将输入非阻塞写入容量为 64 的 Channel。溢出累加原子计数，由消费者批量持久化；不为每次溢出创建新协程。
4. 单消费者调用仓库；仓库再次检查设置，防止关闭采集或删除数据之后排队事件继续入库。
5. 解析器检查包名、标题与完整格式，转换为候选；无法识别则返回 null。
6. 在事务中计算通知键摘要和完整事件指纹。完全相同的来源键、标题、正文及 postTime 重放不重复入库。
7. 相同通知键附近三分钟内的新事件标记 `POSSIBLE_DUPLICATE`。不因金额相同、时间接近就合并不同事件。
8. 已验证且置信度达到阈值才可能自动确认；当前实验解析器 verified=false，全部需要核对。成功入库后根据设置发提醒。

目前生产白名单只包含微信和支付宝包名。包名与标题校验不等同于发布渠道/签名核验；真正启用真实格式前必须补齐样本与来源验证。没有短信、无障碍、OCR 或读取支付账户的能力。

`PaymentTestLab` 复用微信/支付宝解析器，银行采用专用模拟包名 `cn.foldledger.test.bank` 与“测试银行”标题。该包名不注册到生产白名单。模拟通过只证明合成案例的解析逻辑，不证明银行支付通知可采集。

## 6. 账本修订与并发约束

`LedgerRepository.mutation` 串行化通知入库、人工修改、状态调整、导出和清空；多表变更使用 Room 事务。人工保存会验证金额和商户，增加修订号并写入审计，保留原始观察。

`ReportService` 有独立互斥锁，串行化生成、发送、配置修改和文件清理。它读取主账本的日报查询结果后形成快照，发送过程中不会追踪之后的修订。两库没有跨数据库事务，因此使用唯一报告 ID 和先记录后推进游标的恢复策略。

`SettingsStore.reminderMutex` 由通知出口和开关修改共享，避免“读到开启 → 用户关闭 → 仍然发通知”的竞态。不要绕过统一出口直接调用 `NotificationManager.notify` 或创建新的提醒通道。

## 7. 应用锁、界面与提醒

### 7.1 认证和界面状态

`MainActivity` 使用 `BiometricPrompt` API，但允许认证类型仅为 `DEVICE_CREDENTIAL`，即系统密码、PIN、图案。库名称及合并的兼容权限不表示会调用指纹。

`SessionViewModel` 初始锁定，只在内存保存认证状态和草稿；真实进入后台时清空草稿、选择与搜索，配置重建时保留会话。进程重启不恢复敏感草稿。邮箱表单属于页面临时状态，离开后台后需要重新进入配置页。

主窗口固定 `FLAG_SECURE`。导出先选文件位置，再认证后写入；邮箱设置及全量删除需要再次认证。认证取消不得执行待处理动作。数据删除或邮箱发送成功等状态不能代替认证成功。

界面按当前窗口宽度适配，不按手机型号切换业务。列表滚动状态在分栏条件外持有，以支持窄屏详情返回及窗口切换。尚未实现专门的折痕遮挡避让。

### 7.2 提醒设置键

| DataStore 键 | 默认值 | 行为 |
| --- | --- | --- |
| `capture` / `sources` | false / 空集合 | 采集总开关与授权来源 |
| `reminders` | true | 本应用提醒总开关，关闭撤销已有成功/待确认通知及 Toast |
| `success` | false | 已确认记账成功通知 |
| `review` | true | 待确认/可能重复通知 |
| `inApp` | true | 操作 Toast 和 Snackbar，同时受总开关控制 |
| `connected` / `disconnected` / `parsed` | 0 | 最近健康事件时间 |
| `dropped` / `errors` | 0 | 溢出和处理错误累计 |

提醒还需 Android 通知权限与渠道许可。通知默认静默，无金额或商户信息；成功/待确认分别使用 ID 2/1。关闭分类只撤销对应通知，重开总开关不重置分类偏好。

关闭提醒不关闭采集或日报邮件。邮箱结果、表单错误和测试结果属于主动查看的页面内容；邮件状态不另外发送系统通知。系统认证和不可逆删除确认不属于可关闭提醒。

## 8. 每日日报与发送状态机

### 8.1 生成与调度

`ReportSchedule.ensure` 使用唯一周期名 `daily-reports`，首次延迟至次日北京时间约 00:05，之后周期为 24 小时，KEEP 不替换已注册任务。实际执行受系统调度影响，不能承诺每日精确到点。

`report-catch-up` 是唯一一次性补生成任务，应用启动或页面操作可触发。生成无需网络，每次从加密配置中的 nextDate 开始，补齐所有已结束的自然日，每批最多 31 日；仍有积压返回重试。

每日报告先读取当天非忽略账目，生成带 BOM 的 CSV，用 AtomicFile 写入，再插入唯一发送记录，最后推进游标。写入失败回滚；游标未更新而进程中断时，下一次按已有 ID 复用快照。生成文件后、插入记录前中断的文件可在下一次生成同日期时覆盖。

暂停会停止正式日报发送；恢复启用从当天重新开始生成，已生成待发文件继续处理。没有账目的日期也生成零汇总。预览昨日只读当前账本，不创建待发文件或邮件。

### 8.2 状态转换与故障恢复

| 原状态 / 情况 | 下一步 | 文件处理 |
| --- | --- | --- |
| PENDING，尚未连接或认证失败 | 保持 PENDING，网络约束下指数退避重试 | 保留 |
| 连接成功，准备提交 DATA | 先持久化 SENDING，再提交 | 保留 |
| SMTP 接受消息 | 先持久化 SENT，再清理并标记 cleaned | 删除本机日报 |
| 提交后异常或恢复时仍为 SENDING | UNKNOWN，停止自动重发 | 保留供核对 |
| 文件缺失/摘要不符 | UNKNOWN，不发送 | 不使用损坏内容 |
| SENT 但 cleaned=false | 只重试清理，绝不再发邮件 | 成功后删除并标记 |
| 用户确认 UNKNOWN 已收到 | SENT 后清理 | 删除 |
| 用户明确要求 UNKNOWN 重发 | 校验原文件后改 PENDING | 保留并重发，可能重复 |

`report-delivery` 使用有网络约束的唯一工作链和 APPEND_OR_REPLACE，不主动打断正在发送的邮件。初始退避为 30 分钟。更改配置后，已有重试任务可能仍需等待调度。

固定 Message-ID 方便核对，但 SMTP 没有应用级幂等保证。服务器返回成功之后仍可能退信；本应用不读取邮箱、不能监控退信或证明邮件已进入收件箱。

### 8.3 SMTP 与测试邮件

`SmtpMailer` 支持隐式 TLS 和必须升级成功的 STARTTLS，启用证书主机名校验和 TLS 1.2/1.3，不提供跳过校验选项。连接超时 15 秒，读写超时 30 秒。发送前回调必须先写 SENDING；DATA 已成功后 QUIT 失败不回退为发送失败。

MIME 邮件由中文标题、文本说明和 CSV 附件组成。账号/授权码只用于认证，不写进正文、任务参数或日志。只允许一个发件地址和一个收件地址；输入校验拒绝换行注入和地址列表。

测试邮件通过相同队列与 SMTP 发送路径，附件不含账目，成功后也清理本机文件。它可以在每日邮件关闭时显式发送。现有代码不支持 OAuth-only 邮箱登录。

## 9. 删除、安全与权限

| 操作 | 删除内容 | 保留内容 |
| --- | --- | --- |
| 日报被服务器接受 | 对应本机日报 CSV | 原账本、发送元数据、邮箱副本 |
| 清除邮箱配置与日报文件 | 加密邮箱配置、全部日报文件及发送记录 | 原账本、手工导出文件、邮箱副本 |
| 删除全部本地数据 | 上述邮件数据 + 账目、来源、审计；重置采集设置 | 用户另存的导出文件、已发邮件 |

发送与删除共享服务锁，已开始发送的邮件可能先被服务器接收，无法撤回。文件普通删除不等于闪存安全擦除。系统备份和设备迁移通过 Manifest 与 data_extraction_rules 禁用，数据库加密与可恢复备份尚未实现。

POST_NOTIFICATIONS 用于可选应用提醒，通知使用权通过系统单独授权，INTERNET 用于邮件。WorkManager 合并网络状态、唤醒锁、开机接收及前台服务等权限；当前 Worker 未进入常驻前台服务模式。没有短信或无障碍权限。

## 10. 测试与验证

| 套件 | 数量 | 覆盖范围 |
| --- | --- | --- |
| DomainTest | 4 | 金额、来源限制、实验规则、CSV 安全 |
| RepositoryTest | 8 | 并发去重、修订审计、不同方向汇总、删除、导出、模拟不入账 |
| SessionTest | 2 | 初始锁定与后台敏感状态清理 |
| ReportTest | 10 | 日界线、不可变快照、游标恢复、发送失败/不明、清理与暂停 |
| SmtpMessageTest | 2 | 真实 MIME 编解码、授权码不泄漏、邮箱输入边界 |
| PaymentTestLabTest | 3 | 12 场景方向金额、非法金额拒绝、银行隔离 |
| RemindersTest | 5 | 总/分类开关、竞态、权限、Toast 控制 |
| 合计 | 34 | 本地自动化回归 |

Room 和通知行为在 Robolectric API 33 环境验证。日报发送状态测试使用替身发送器，不发送真实邮件；MIME 测试使用真实 Angus 编解码，但不建立 SMTP 连接。不能据此声称完成真实支付通知、Keystore、系统认证 UI、厂商后台或邮件投递测试。

测试报告在 `app/build/reports/tests/testDebugUnitTest/index.html`，lint 报告在 `app/build/reports/lint-results-debug.html`。现有 lint 21 项警告主要是版本更新建议和 KTX 写法建议，另有 1 项状态类型提示；具体结果以[验证记录](VERIFICATION.md)为准。

本轮只补文档与中文注释，不增加行为测试。通过注释前后的词法内容/XML 结构比对，以及现有构建、测试、lint 检查确认未引入业务修改。

## 11. 扩展与维护流程

### 新增真实支付来源

1. 收集脱敏的真实通知样本，记录手机系统、来源版本和渠道，覆盖付款、收款、退款、转账、红包及非交易消息。
2. 明确包名与发布渠道/签名核验策略，新增独立解析器，不用通用金额正则替代来源规则。
3. 先添加误判回归与来源伪造测试；未达到验证条件保持 verified=false，不直接自动确认。
4. 完成真机验收后才注册生产白名单和来源设置，更新样本与支持范围文档。银行模拟不能直接改名后当作真实银行解析器。

### 修改数据结构或发送机制

- Room 实体变化必须新增版本与迁移、保留旧 schema，并使用旧版本数据验证升级。
- 调整日报日期、文件结构或更正报告时，需要定义旧快照和新报告的版本关系，不能覆盖已发日报。
- 新发送器应实现 ReportMailer，并保持“持久化 SENDING 后才提交”的约定；若迁移 HTTPS 网关，需要网关同时提供幂等契约。
- 新提醒必须经过统一设置和发布入口，补充关闭后的撤销、并发和系统权限检查。

### 文档与注释维护

- 英文 README 是项目入口，中文与日文 README 是配套使用文档；界面当前仍为中文。
- 中文注释解释职责、数据单位、调用约束和失败恢复，不逐行复述赋值和语法。
- 业务变化同时更新本文、对应使用文档、测试和验收说明。历史进度及原始设计书不等于当前功能承诺。
- 不改生成的 Room schema 内容、构建输出或第三方 Gradle 启动脚本来添加注释；生成结果由构建工具维护。

## 12. 常见排查

| 问题 | 检查顺序 |
| --- | --- |
| 采集没有记录 | 采集开关 → 来源选择 → 系统通知使用权 → 来源是否发出完整交易通知 → 当前实验规则是否匹配 |
| 测试银行能通过，真实银行无记录 | 这是预期隔离行为；真实银行规则尚未启用 |
| 没有提醒 | 总开关 → 对应分类 → 系统通知权限 → 渠道权限；测试结果页面仍可查看 |
| 邮件未发送 | 已保存配置 → 每日邮件开关/显式测试 → 网络与后台限制 → 发送记录 → SMTP 账号与授权码 |
| 显示结果不明 | 先检查收件箱及垃圾邮件，再确认收到或明确重发，不直接反复点击测试 |
| 邮箱配置解密失败 | 清除邮件配置与日报数据后重新填写；不得绕过验证读取为明文 |
| 关闭提醒仍要求密码 | 密码认证是访问控制，不属于可关闭的提醒 |
| 发送后账目仍在 | 正常：只删除日报附件，原账本保留 |

真实设备验收与尚未完成的功能分别见[验收清单](ACCEPTANCE.md)和[开发进度](PROGRESS.md)。
