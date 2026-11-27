package io.github.sparkcyf.voicesipagent.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Executors
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Optional email notifications (missed calls, SMS/OTP, IP changes) sent through the user's OWN
 * SMTP account — typically Gmail with a free Google app password (2-step verification required).
 * OFF by default; the gateway works fully without it. Mail goes only to the configured
 * recipient (the user's own email) and never contains a password or secret.
 *
 * The parent agent watches the user's Gmail for these exact subjects, so the subject formats
 * are part of the contract — do not change them without telling the parent:
 * - missed call: `[VSA Gateway] Missed call from <number>`
 * - SMS:         `[VSA Gateway] SMS from <number>`
 * - IP change:   `[VSA Gateway] New IP: <ip>`
 * - test:        `[VSA Gateway] Test email`
 * - low battery: `[VSA Gateway] Low battery: <pct>%` (side-menu feature 3)
 * - low balance: `[VSA Gateway] Low balance: <amount>` (side-menu feature 9)
 */
data class EmailConfig(
    val host: String,
    val port: Int,
    /** "starttls" (port 587) or "ssl" (port 465). */
    val security: String,
    val user: String,
    val password: String,
    val recipient: String,
)

object EmailSubjects {
    fun missedCall(number: String?): String =
        "[VSA Gateway] Missed call from ${number?.ifBlank { null } ?: "unknown number"}"

    fun sms(from: String?): String =
        "[VSA Gateway] SMS from ${from?.ifBlank { null } ?: "unknown number"}"

    fun newIp(ip: String): String = "[VSA Gateway] New IP: $ip"

    fun lowBattery(pct: Int): String = "[VSA Gateway] Low battery: $pct%"

    /** [amount] is the parsed balance formatted with up to 3 decimals, e.g. "4.5" or "12.500". */
    fun lowBalance(amount: String): String = "[VSA Gateway] Low balance: $amount"

    const val TEST = "[VSA Gateway] Test email"
}

object EmailBodies {
    internal fun formatTime(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timeMs))

    private fun simText(slot: Int?): String = slot?.let { "SIM ${it + 1}" } ?: "unknown SIM"

    fun missedCall(number: String?, timeMs: Long, slot: Int?): String = buildString {
        appendLine("Missed call from ${number ?: "unknown number"}")
        appendLine("Time: ${formatTime(timeMs)}")
        appendLine("SIM: ${simText(slot)}")
        appendLine()
        append("Sent by the Voice SIP Agent gateway on your Infinix.")
    }

    fun sms(from: String?, timeMs: Long, slot: Int?, text: String): String = buildString {
        appendLine("SMS from ${from ?: "unknown number"}")
        appendLine("Time: ${formatTime(timeMs)}")
        appendLine("SIM: ${simText(slot)}")
        appendLine()
        appendLine(text)
        appendLine()
        append("Sent by the Voice SIP Agent gateway on your Infinix.")
    }

    fun newIp(ip: String, allAddresses: List<LocalAddress> = emptyList()): String = buildString {
        appendLine("The gateway's IP address changed.")
        appendLine("New address: $ip")
        if (allAddresses.isNotEmpty()) {
            appendLine()
            appendLine("All gateway addresses now:")
            for (a in allAddresses) appendLine("- ${a.label}: ${a.address}")
            appendLine()
            append("Update Linphone (account 101) to the address that matches how the iPhone connects: hotspot address when nearby, VPN address when far away.")
        } else {
            appendLine()
            append("Update Linphone (account 101) to this address if the iPhone connects directly.")
        }
    }

    fun lowBattery(pct: Int, timeMs: Long, charging: Boolean): String = buildString {
        appendLine("The Infinix gateway battery is low: $pct%.")
        appendLine("Time: ${formatTime(timeMs)}")
        appendLine("Charging: ${if (charging) "yes" else "no"}")
        appendLine()
        append("Plug the Infinix in — when its battery dies, calls stop reaching the iPhone.")
    }

    fun lowBalance(amountTnd: Double, timeMs: Long): String = buildString {
        appendLine("The gateway SIM balance is low: ${"%.3f".format(Locale.US, amountTnd)} TND.")
        appendLine("Time: ${formatTime(timeMs)}")
        appendLine()
        append("Top up the SIM so outgoing calls and SMS keep working.")
    }

    const val TEST_BODY =
        "This is a test email from the Voice SIP Agent gateway on your Infinix.\n\n" +
            "If you can read this, missed-call, SMS and IP-change notifications will reach this inbox."
}

/**
 * Sends one plain email per event on a daemon thread, so a slow SMTP server never blocks the
 * gateway worker. The result callback runs on the sender thread; failures are reported, never
 * thrown. No secrets are logged.
 */
class EmailNotifier(
    private val sendImpl: (EmailConfig, String, String) -> Unit = ::sendBlocking,
) {
    /** Sends [subject]/[body]; [onResult] gets (success, error message or null). */
    fun send(config: EmailConfig, subject: String, body: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        executor.execute {
            try {
                sendImpl(config, subject, body)
                onResult(true, null)
            } catch (e: Exception) {
                AppLog.w("email to ${config.recipient} failed: ${e.javaClass.simpleName}")
                onResult(false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "email-notifier").apply { isDaemon = true }
        }

        /** Null when the user has not configured mail (host/user/password blank). */
        fun configFrom(s: GatewaySettings): EmailConfig? {
            val host = s.smtpHost.trim()
            val user = s.smtpUser.trim()
            if (host.isEmpty() || user.isEmpty() || s.smtpPassword.isEmpty()) return null
            return EmailConfig(
                host = host,
                port = s.smtpPort,
                security = s.smtpSecurity.trim().lowercase().takeIf { it == "ssl" } ?: "starttls",
                user = user,
                password = s.smtpPassword,
                recipient = s.emailRecipient.trim().ifEmpty { user },
            )
        }

        internal fun sendBlocking(config: EmailConfig, subject: String, body: String) {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.host", config.host)
                put("mail.smtp.port", config.port.toString())
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "15000")
                if (config.security == "ssl") put("mail.smtp.ssl.enable", "true")
                else put("mail.smtp.starttls.enable", "true")
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(config.user, config.password)
            })
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.user))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.recipient))
                setSubject(subject)
                setText(body)
            }
            Transport.send(message)
        }
    }
}
