package io.github.sparkcyf.voicesipagent.app

/**
 * Remote-command protocol over SIP MESSAGE (side-menu features 1, 2, 8, 11). One command per
 * message, keyword case-insensitive, from the registered phone ONLY — [io.github.sparkcyf.voicesipagent.sip.SipUserAgent]
 * enforces digest auth + registered-sender before this parser is ever reached, so a command
 * that arrives here is already trusted. Unknown or malformed input returns null; the runtime
 * answers it with a short usage hint instead of doing anything.
 *
 *   SMS <number> <text>   send an SMS via the gateway SIM (default SIM); number has no spaces
 *   USSD [code]           run a USSD code; no code = the default (*124#, Ooredoo balance)
 *   RESTART               restart the gateway service and SIP stack
 *   FIND [STOP]           ring the Infinix at max volume for up to 30 s (STOP silences it)
 *
 * Example (typed in Linphone as a chat message to the gateway): `SMS +21629154710 Salam, kifek?`
 */
sealed class SipCommand {
    data class SendSms(val destination: String, val text: String) : SipCommand()
    data class RunUssd(val code: String?) : SipCommand()
    object Restart : SipCommand()
    data class FindPhone(val stop: Boolean) : SipCommand()
}

object SipCommandParser {
    private val USSD_CODE = Regex("""^[*#][0-9*#]*#$""")

    fun parse(body: String): SipCommand? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val firstSpace = trimmed.indexOf(' ')
        val keyword = (if (firstSpace < 0) trimmed else trimmed.substring(0, firstSpace)).uppercase()
        val rest = if (firstSpace < 0) "" else trimmed.substring(firstSpace + 1).trim()
        return when (keyword) {
            "SMS" -> {
                val split = rest.indexOf(' ')
                if (split <= 0) return null
                val destination = rest.substring(0, split).trim()
                val text = rest.substring(split + 1).trim()
                if (destination.isEmpty() || text.isEmpty()) null
                else SipCommand.SendSms(destination, text)
            }
            "USSD" -> when {
                rest.isEmpty() -> SipCommand.RunUssd(null)
                USSD_CODE.matches(rest) -> SipCommand.RunUssd(rest)
                else -> null
            }
            "RESTART" -> if (rest.isEmpty()) SipCommand.Restart else null
            "FIND" -> when (rest.uppercase()) {
                "" -> SipCommand.FindPhone(stop = false)
                "STOP" -> SipCommand.FindPhone(stop = true)
                else -> null
            }
            else -> null
        }
    }

    /** Short usage hint sent back to the phone for an unknown command. */
    const val USAGE = "Commands: SMS <number> <text> | USSD [code] | RESTART | FIND [STOP]"
}
