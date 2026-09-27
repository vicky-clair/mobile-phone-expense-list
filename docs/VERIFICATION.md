# 费用统计清单验证记录

日期：2026-09-27。版本：0.3.0 / versionCode 3。此次新增提醒总开关、操作提示开关及支付测试面板，保留每日日志邮件与系统锁屏凭据认证。

同日文档维护后重新验证：新增现行开发文档、英文主 README 与中日文辅助 README，补齐中文代码注释。应用版本及业务行为未改变；下列 APK 校验值对应文档维护后的重新构建产物。

## 构建与安装包

环境：Windows、JDK 17.0.2、Gradle 9.3.1、AGP 9.1.1、KSP 2.3.9、Android Build Tools 36.1.0。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

结果：`BUILD SUCCESSFUL`。本次下载依赖时额外给 Gradle 设置连接超时 15 秒、读取超时 30 秒，未修改测试行为。

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

- 大小：34,305,712 字节（调试构建，包含开发工具，不能代表正式版包体）。
- SHA-256：`3596b454dab3e17b572b7990c5e10a472cf67b4d29c9943dc5c5f1df97e75b9b`。
- `apksigner verify --verbose`：通过，APK Signature Scheme v2。
- 从 APK 中提取的配置：包名 `cn.foldledger`，minSdk 33，targetSdk 36，compileSdk 37。
- 应用标签已核实为“费用统计清单”。
- 权限：POST_NOTIFICATIONS、INTERNET、USE_BIOMETRIC，兼容库合并的 USE_FINGERPRINT，以及 WorkManager 的网络状态、唤醒锁、开机接收、前台服务和 AndroidX 内部接收器权限。代码认证只允许 DEVICE_CREDENTIAL，不请求指纹；任务未调用前台服务模式。没有短信或无障碍权限。
- 主账本 Room v1 保持兼容，日报使用新增独立 ReportDatabase v1；schema 已生成并保存。

通过手机系统文件管理器打开 APK 安装，或连接已授权调试的设备后运行：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次打开前请设置系统锁屏密码、PIN 或图案。应用只接受系统锁屏凭据，不使用指纹；通知来源、采集和每日邮件默认关闭。

## 自动化检查

| 测试套件 | 测试数 | 失败 / 错误 / 跳过 |
| --- | --- | --- |
| DomainTest | 4 | 0 / 0 / 0 |
| RepositoryTest | 8 | 0 / 0 / 0 |
| SessionTest | 2 | 0 / 0 / 0 |
| ReportTest | 10 | 0 / 0 / 0 |
| SmtpMessageTest | 2 | 0 / 0 / 0 |
| PaymentTestLabTest | 3 | 0 / 0 / 0 |
| RemindersTest | 5 | 0 / 0 / 0 |
| 合计 | 34 | 0 / 0 / 0 |

覆盖金额精度与边界、来源和格式限制、非交易拒绝、CSV 转义与公式防护、并发重放幂等、同额消费独立保留、同键复用核对、人工修订不被旧通知覆盖、观察事件和审计保留、忽略撤销、不同交易类型统计、来源开关、删除后停止采集、后台锁定清除敏感草稿。

Room 仓库测试在 Robolectric Android 13 / API 33 环境运行，不是 Fold5 真机测试。测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。

日报测试覆盖北京时间半开区间、跨年逐日补生成、确认/待核对/转账统计、CSV 公式防护、快照不变、游标恢复幂等、服务器接收后清理且保留账本、连接失败重试、响应丢失停止自动重发、SENDING 中断恢复、人工确认/重试、已发文件清理恢复、暂停与显式测试邮件、地址变更限制、文件损坏阻止发送。真实 Angus MIME 编解码测试验证中文标题/附件、CSV 内容、固定 Message-ID 与授权码不进入邮件正文。

新增检查覆盖关闭总开关取消已有通知并阻止后续通知、分类开关只清除对应通知、并发关闭不补发、拒绝系统权限不通知、操作 Toast 遵循两个开关、三来源四场景金额与方向正确、非法金额拒绝、银行模拟与生产解析隔离、模拟测试不写入账本。

Android lint：0 错误、21 警告、1 提示。18 项警告是工具/依赖更新或 targetSdk 版本建议，3 项是 Uri/SharedPreferences 的 KTX 写法建议；另有 `mutableIntStateOf` 提示。SharedPreferences 显式 commit 用于检查配置保存成功。targetSdk 36 按产品文档保留，未关闭检查隐藏警告。报告：`app/build/reports/lint-results-debug.html`。

本次文档维护后完整检查 `BUILD SUCCESSFUL in 48s`，56 个任务（31 执行，25 最新），日志 `build-documentation.log`。assembleDebug、testDebugUnitTest、lintDebug 均通过，34 项测试无失败或错误，APK v2 签名再次验证通过。

注释前后对比：34 个 Kotlin/Gradle/XML 文件的非注释代码或 XML 结构一致；全部 27 个 Kotlin 源码与测试文件包含中文文档注释。新增开发文档与三语 README 的 43 处本地链接、代码围栏及 UTF-8 内容检查通过。Gradle properties 仅补解释性注释，生成 schema 和第三方 Wrapper 启动脚本未手工修改。调试 APK 因源码行号等构建信息变化，校验值随重建更新。

## 验证边界

尚未连接真机，未验证系统密码交互、设备 Keystore、真实 SMTP TLS 连接/收件、One UI 后台限制、折叠切换、耗电或字体缩放。发送状态机测试使用替身发送器，MIME 测试使用真实邮件库；本轮未配置真实邮箱、未发送真实邮件。安装后先按 `DAILY_MAIL.md` 配置并发送测试邮件，再启用日报。

WorkManager 不保证准点执行；SMTP 服务器接收不等于收件箱投递，无法保证严格恰好发送一次。实验通知解析仍只覆盖合成格式，候选默认待确认；不代表真实支付宝/微信格式全面支持。设备验收步骤见 `ACCEPTANCE.md`。
