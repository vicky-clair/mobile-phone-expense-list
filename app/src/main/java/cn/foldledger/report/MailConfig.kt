package cn.foldledger.report

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 个人 SMTP 配置；nextDate 是待生成的最早日报日期，授权码只通过加密存储保存。 */
data class MailConfig(
    val enabled: Boolean = false,
    val host: String = "", val port: Int = 465, val startTls: Boolean = false,
    val username: String = "", val password: String = "",
    val from: String = "", val recipient: String = "",
    val nextDate: String = ""
) {
    /** 验证主机、端口、授权码和单一收件地址；错误仅描述格式，不回显秘密。 */
    fun validate() {
        require(host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}"))) { "请输入 SMTP 主机名，不含协议或路径" }
        require(port in 1..65535) { "端口应为 1–65535" }
        require(username.isNotBlank() && password.isNotBlank()) { "请填写 SMTP 账号与授权码" }
        require(username.length <= 320 && password.length <= 1024 && !username.contains('\n') && !username.contains('\r')) { "账号或授权码格式错误" }
        require(validAddress(from) && validAddress(recipient)) { "发件和收件地址各填写一个完整邮箱" }
    }
    companion object {
        /** 限制为一个邮箱地址，拒绝换行与收件人列表，避免邮件头注入。 */
        fun validAddress(value: String): Boolean = value.length <= 254 &&
            value.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}"))
    }
}

/** 邮箱配置读写接口；生产使用 Keystore 实现，测试替换为内存实现。 */
interface MailConfigStore {
    /** 解码随机 IV 与 AES-GCM 密文；校验或密钥失败直接抛错，不降级为明文读取。 */
    fun read(): MailConfig
    /** 使用新随机 IV 加密完整配置，并同步确认持久化成功后才返回。 */
    fun write(config: MailConfig)
    /** 清除本地加密配置；日报服务负责同步清理文件和发送记录。 */
    fun clear()
}


/** 将整个 SMTP 配置加密保存，不向 WorkManager 输入或日志传递账号密钥。 */
class SecureMailConfig(context: Context) : MailConfigStore {
    private val prefs = context.getSharedPreferences("mail_secure", Context.MODE_PRIVATE)
    /** 读取或创建 Android Keystore AES 密钥；密钥不要求每次 UI 认证，以允许后台发送。 */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ledger-mail-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ledger-mail-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    /** 解码随机 IV 与 AES-GCM 密文；校验或密钥失败直接抛错，不降级为明文读取。 */
    override fun read(): MailConfig {
        val encoded = prefs.getString("config", null) ?: return MailConfig()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // 存储格式为 12 字节随机 IV 加带 128 位认证标签的密文，不能复用加密 IV。
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return MailConfig(json.getBoolean("enabled"), json.getString("host"), json.getInt("port"), json.getBoolean("tls"),
            json.getString("user"), json.getString("password"), json.getString("from"), json.getString("to"), json.getString("next"))
    }
    /** 使用新随机 IV 加密完整配置，并同步确认持久化成功后才返回。 */
    override fun write(config: MailConfig) {
        val json = JSONObject().put("enabled", config.enabled).put("host", config.host).put("port", config.port)
            .put("tls", config.startTls).put("user", config.username).put("password", config.password)
            .put("from", config.from).put("to", config.recipient).put("next", config.nextDate)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("config", encoded).commit()) { "无法保存邮箱配置" }
    }
    /** 清除本地加密配置；日报服务负责同步清理文件和发送记录。 */
    override fun clear() { check(prefs.edit().clear().commit()) }
}
