package cn.foldledger.report

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.File
import java.util.Properties

/** 发送器抽象，便于在测试中模拟成功、连接失败和服务器响应丢失。 */
fun interface ReportMailer {
    /** 连接后、提交 SMTP DATA 前调用 beforeSend，先持久化 SENDING 才允许提交邮件。 */
    suspend fun send(config: MailConfig, report: DailyReport, file: File, beforeSend: suspend () -> Unit)
}

/** 个人 SMTP 发送器：强制 TLS/STARTTLS、主机身份验证与超时，不支持明文回退。 */
class SmtpMailer : ReportMailer {
    /** 连接后、提交 SMTP DATA 前调用 beforeSend，先持久化 SENDING 才允许提交邮件。 */
    override suspend fun send(config: MailConfig, report: DailyReport, file: File, beforeSend: suspend () -> Unit) {
        config.validate()
        val props = Properties().apply {
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.ssl.enable", (!config.startTls).toString())
            setProperty("mail.smtp.starttls.enable", config.startTls.toString())
            setProperty("mail.smtp.starttls.required", config.startTls.toString())
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            setProperty("mail.smtp.connectiontimeout", "15000")
            setProperty("mail.smtp.timeout", "30000")
            setProperty("mail.smtp.writetimeout", "30000")
            setProperty("mail.smtp.sendpartial", "false")
        }
        val session = Session.getInstance(props)
        val message = makeMessage(session, config, report, file)
        val transport = session.getTransport("smtp")
        try {
            transport.connect(config.host, config.port, config.username, config.password)
            beforeSend()
            transport.sendMessage(message, message.allRecipients)
        } finally {
            // DATA 已被服务器接受后，QUIT 失败不应将已接受结果降级为发送失败。
            runCatching { transport.close() }
        }
    }

    /** 构造中文标题、说明与 CSV 附件的 MIME 邮件；授权码仅用于连接认证，不进入内容。 */
    internal fun makeMessage(session: Session, config: MailConfig, report: DailyReport, file: File): MimeMessage =
        object : MimeMessage(session) {
            /** 同一报告保持固定 Message-ID 方便核对；不能依赖邮箱据此保证去重。 */
            override fun updateMessageID() { setHeader("Message-ID", "<${report.id}@expense-list.local>") }
        }.apply {
            setFrom(InternetAddress(config.from, true))
            setRecipient(Message.RecipientType.TO, InternetAddress(report.recipient, true))
            setSubject("费用统计清单 · ${if (report.isTest) "测试邮件" else report.day}", "UTF-8")
            setContent(MimeMultipart().apply {
                addBodyPart(MimeBodyPart().apply { setText(
                    if (report.isTest) "这是费用统计清单的邮箱配置测试，不包含账目。"
                    else "附件是 ${report.day} 北京时间全天的费用日志。待核对账目单独标注，不计入已确认支出。日报是生成时快照。邮件服务器确认接收后，手机会删除本次日报文件，保留原账本。", "UTF-8") })
                addBodyPart(MimeBodyPart().apply { attachFile(file); fileName = "费用统计清单-${report.day}${if (report.isTest) "-测试" else ""}.csv" })
            })
            saveChanges()
        }
}
