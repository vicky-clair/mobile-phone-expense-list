package cn.foldledger

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cn.foldledger.security.SessionViewModel
import cn.foldledger.ui.LedgerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单 Activity 容器：负责系统密码认证、私密窗口、导出授权与邮箱设置入口。 */
class MainActivity : FragmentActivity() {
    private val session: SessionViewModel by viewModels()
    private var lockMessage by mutableStateOf("使用手机锁屏密码、PIN 或图案解锁（不使用指纹）")
    private var mailSettings by mutableStateOf(false)
    private var systemRevision by mutableIntStateOf(0)
    private var prompt: BiometricPrompt? = null
    private var pendingAuthentication: (() -> Unit)? = null
    private val app get() = application as LedgerApp
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { systemRevision++ }
    // 文件选择器会使应用暂时离开前台；返回选定位置后再认证，认证成功才写入明文。
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) authenticate("验证后导出明文 CSV") {
            session.unlocked = true
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { app.repository.export(it) }
                    }
                }
                cn.foldledger.capture.Reminders.feedback(this@MainActivity, app.settings,
                    if (result.isSuccess) "CSV 已保存到您选择的位置" else "导出失败，请检查所选位置是否可写") {
                    // 导出结束时若用户已切换到支付 App，不在其他应用上弹出结果提示。
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && session.unlocked
                }
            }
        }
    }
    /** 启用截图保护，建立系统认证回调，再根据会话状态显示锁定页或账本。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            /** 仅认证成功后消费一次待执行操作，不能在取消认证时执行。 */
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val action = pendingAuthentication
                pendingAuthentication = null
                action?.invoke()
            }
            /** 撤销待执行操作并显示系统错误说明，不暴露账目内容。 */
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                pendingAuthentication = null
                lockMessage = errString.toString()
            }
        })
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) {
                    if (session.unlocked && mailSettings) cn.foldledger.ui.MailSettingsScreen(app) { mailSettings = false }
                    else if (session.unlocked) LedgerScreen(app, session, systemRevision,
                        onMail = { authenticate("验证后配置日报邮箱") { session.unlocked = true; mailSettings = true } },
                        onExport = { export.launch("费用统计清单-${java.time.LocalDate.now()}.csv") },
                        onDelete = { authenticate("验证后删除全部本地账本") {
                            lifecycleScope.launch { withContext(Dispatchers.IO) { app.reports.clear(); app.repository.deleteAll() }; session.lock()
                                getSystemService(NotificationManager::class.java).cancelAll() }
                        } },
                        onPermission = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) })
                    else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("费用统计清单", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(16.dp))
                        Text("您的账本，仅您可见", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(lockMessage)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { authenticate("解锁费用统计清单") { session.unlocked = true } }) { Text("输入锁屏密码") }
                        TextButton(onClick = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) { Text("设置系统锁屏方式") }
                        Text("应用锁不影响后台采集。截图与最近任务预览已保护。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    /** 只接受系统锁屏密码、PIN 或图案；DEVICE_CREDENTIAL 不会要求指纹。 */
    private fun authenticate(title: String, onSuccess: () -> Unit) {
        val authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            lockMessage = "请先在手机系统设置中启用锁屏密码、PIN 或图案"
            return
        }
        pendingAuthentication = onSuccess
        prompt?.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators).build())
    }
    /** 返回前台时刷新系统权限状态，具体敏感操作仍需认证。 */
    override fun onResume() { super.onResume(); systemRevision++ }
    /** 真正进入后台立即锁定；配置重建不清空 ViewModel 中的编辑状态。 */
    override fun onStop() {
        cn.foldledger.capture.Reminders.cancelFeedback()
        if (!isChangingConfigurations) { session.lock(); mailSettings = false }
        super.onStop()
    }
}
