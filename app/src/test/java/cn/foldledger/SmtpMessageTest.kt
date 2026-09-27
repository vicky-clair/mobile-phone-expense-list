package cn.foldledger

import cn.foldledger.report.*
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Properties

/** 使用真实 Angus MIME 编解码验证邮件内容，不建立 SMTP 连接。 */
class SmtpMessageTest {
    /** 中文标题、文件名及 CSV 编解码保持一致，邮件正文不得含授权码。 */
    @Test fun realMimeEncodingRoundTripsChineseCsvWithoutCredentialLeak() {
        val file = Files.createTempFile("mail-test", ".csv").toFile()
        try {
            val csv = "\uFEFF商户,金额\r\n早餐,12.30\r\n"
            file.writeText(csv)
            val config = MailConfig(host = "smtp.example.com", username = "sender@example.com", password = "private-authorization-code",
                from = "sender@example.com", recipient = "receiver@example.com")
            val report = DailyReport("daily-2026-09-26", "2026-09-26", config.recipient, createdAt = 1L, digest = "test")
            val session = Session.getInstance(Properties())
            val output = ByteArrayOutputStream()
            SmtpMailer().makeMessage(session, config, report, file).writeTo(output)
            val decoded = MimeMessage(session, ByteArrayInputStream(output.toByteArray()))
            assertEquals("费用统计清单 · 2026-09-26", decoded.subject)
            assertEquals("receiver@example.com", decoded.allRecipients.single().toString())
            assertEquals("<daily-2026-09-26@expense-list.local>", decoded.messageID)
            val parts = decoded.content as MimeMultipart
            assertEquals(2, parts.count)
            assertEquals(csv, parts.getBodyPart(1).inputStream.bufferedReader(Charsets.UTF_8).readText())
            assertEquals("费用统计清单-2026-09-26.csv", parts.getBodyPart(1).fileName)
            assertFalse(output.toString("UTF-8").contains(config.password))
        } finally { file.delete() }
    }
    /** 拒绝换行注入及多个收件地址，允许正常的加号邮箱别名。 */
    @Test fun rejectsHeaderInjectionAndMultipleRecipients() {
        assertFalse(MailConfig.validAddress("a@example.com\r\nBcc:b@example.com"))
        assertFalse(MailConfig.validAddress("a@example.com,b@example.com"))
        assertTrue(MailConfig.validAddress("name+ledger@example.com"))
    }
}
