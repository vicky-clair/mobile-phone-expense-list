# Expense Statistics List · 费用统计清单

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

A native Android expense ledger built with Kotlin and Jetpack Compose, with local transaction records, payment-notification parsing, daily CSV email reports, and device-credential protection.

**Version 0.3.0 — development build.** Requires Android 13 or later. Installation is not restricted to Samsung devices, but physical-device compatibility has not been verified. The installed name and current interface are Chinese; translated README files do not add UI localization.

## Features

- **Local ledger:** manual entries, categories, search, review, corrections, audit history, CSV export, and deletion. Amounts use integer fen rather than floating-point values.
- **Notification capture:** explicit source selection, bounded processing, exact-event deduplication, and conservative handling of possible duplicates.
- **App lock:** the phone's screen-lock password, PIN, or pattern. Fingerprints are not requested. Capture and reporting can continue while the interface is locked.
- **Adaptive layout:** a single column on narrow windows and list/detail panels on wider windows.
- **Reminder controls:** a master switch and individual switches for bookkeeping success, pending review, and operation popups. Disabling a category removes its existing reminders and blocks new ones.
- **Daily email:** one CSV per Beijing calendar day, personal SMTP configuration, delivery records, and automatic deletion of the local attachment after server acceptance. The original ledger remains available.
- **Test tools:** 12 WeChat, Alipay, and simulated-bank scenarios covering payments, receipts, refunds, and non-transaction messages; notification tests; and a test-email action using saved SMTP settings.

## Current limitations

WeChat and Alipay use **synthetic, experimental notification formats**. Recognized entries require manual review. The bank simulator is isolated from production capture and does not establish real-bank support. Payment tests neither initiate payments nor write ledger entries.

Network availability, device sleep, shutdown, and force-stop can delay reports. SMTP acceptance does not guarantee inbox delivery. Interrupted submissions become **unknown** and require a mailbox check before retrying.

The ledger and pending CSV files are not encrypted. SMTP configuration is encrypted with Android Keystore. App locking protects the interface, not the database. System backup and transfer of private app data are disabled.

## Build and install

| Component | Project baseline |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper / Android Gradle Plugin | 9.3.1 / 9.1.1 |
| Compose Kotlin plugin / KSP | 2.2.10 / 2.3.9 |
| Android SDK / Build Tools | API 37 / 36.1.0 |
| Minimum / target SDK | 33 / 36 |

Set `ANDROID_HOME`, or set `sdk.dir` in a local `local.properties` file. In the project directory on Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Install it with a phone file manager, or on an authorized debugging device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Keep package ID `cn.foldledger` and the signing key unchanged when upgrading. Release signing is not configured. A temporary compile-SDK override is available as `-Pledger.compileSdk=36`; it does not replace device testing.

## First run

1. Set a screen-lock password, PIN, or pattern on the phone, then unlock the app with that credential.
2. In **Settings / 设置**, select payment sources, enable capture, and grant notification access through Android settings. Capture is disabled and no sources are selected by default.
3. Review recognized entries before counting them in confirmed totals. Manually add missing or unsupported transactions.
4. Under **Reminders / 提醒**, configure the master and category switches. Muting reminders does not stop capture or daily email. Email has its own switch; authentication and deletion confirmations remain available.
5. Under **Payment and receipt tests / 支付与收款测试**, select a source and scenario, load a sample, and run recognition or all 12 checks. Notification tests obey reminder switches and Android permissions.
6. Open **Daily expense logs and email settings / 每日费用日志与邮箱设置**, authenticate again, and save the SMTP host, port, login, authorization code, sender, and recipient. Send a test email before enabling daily email. Never put real authorization codes in source files, chats, or logs.

## Daily report behavior

Reports use `Asia/Shanghai`: 00:00 inclusive to the following 00:00 exclusive. Enabling starts with the current day; the first report is generated the following day. Initial scheduling targets approximately 00:05, and delayed runs catch up date by date.

Each CSV contains confirmed totals and transaction details. Unreviewed entries are marked separately; transfers and repayments are excluded from expenses; ignored entries are excluded from daily reports. Generated reports are immutable snapshots. Later ledger edits do not silently rewrite or resend them.

Implicit TLS and STARTTLS both verify server certificates. This flow requires SMTP username/authorization-code login and does not support OAuth-only accounts.

After SMTP acceptance, the app deletes **only the local report attachment**, retaining the ledger and delivery metadata. It does not delete mailbox copies or manually exported CSV files. See the [daily email guide](docs/DAILY_MAIL.md) for retry and cleanup details.

## Development

Project-owned Kotlin code, tests, build configuration, and Android XML resources have Chinese explanatory comments. Generated Room schemas, build output, and third-party Gradle launcher scripts are not manually annotated.

```text
app/src/main/java/cn/foldledger/
  capture/    Listener, parsers, reminders, isolated test fixtures
  data/       Ledger database, repository, preferences
  domain/     Money, transaction types, hashing, CSV encoding
  report/     Daily snapshots, delivery records, SMTP, workers
  security/   In-memory session and editing state
  ui/         Ledger, settings, test panels
  LedgerApp.kt / MainActivity.kt
app/src/test/java/cn/foldledger/   Local regression tests
app/schemas/                     Exported Room schemas
docs/                            Development and verification documents
```

The regression suite contains **34 tests** for accounting, reminders, parser isolation, report recovery, and MIME encoding. See the [verification record](docs/VERIFICATION.md) for build results and the APK checksum. Local tests do not validate real payment notifications, device Keystore behavior, actual email delivery, or vendor background restrictions.

## Documentation

| Document | Purpose | Language |
| --- | --- | --- |
| [Development guide](docs/DEVELOPMENT.md) | Architecture, data model, concurrency, state machine, maintenance | Chinese |
| [Chinese README](README.zh-CN.md) | Setup and usage companion | Chinese |
| [Japanese README](README.ja.md) | Setup and usage companion | Japanese |
| [Daily email guide](docs/DAILY_MAIL.md) | SMTP setup, date windows, retries and deletion | Chinese |
| [Sample guide](docs/SAMPLES.md) | Synthetic formats and real-sample validation | Chinese |
| [Verification record](docs/VERIFICATION.md) | Build, tests, signing and limitations | Chinese |
| [Device acceptance checklist](docs/ACCEPTANCE.md) | Physical-device checks still required | Chinese |
| [Development progress](docs/PROGRESS.md) | Implementation history and remaining work | Chinese |

The [original Fold5 design document](三星折叠屏自动记账App开发文档.md) is retained as historical requirements. Later user decisions replaced weekly gateway reports with daily personal-SMTP reports and replaced fingerprint preference with device-credential-only authentication. The development guide describes the current implementation.
