package io.github.sparkcyf.voicesipagent.telephony

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/**
 * Sends SMS through the gateway SIM (feature 1: SMS from the iPhone; feature 7: auto-reply to
 * missed calls). Dual-SIM aware: [send] takes the logical SIM slot, resolved to a subscription
 * id via [SimSlots]; null means the system default SMS subscription.
 *
 * The reply to the iPhone ("sent" / "failed") means the radio accepted the message, not that
 * the handset received it — [SmsManager.sendTextMessage] reports no delivery without a
 * PendingIntent, and this gateway deliberately keeps no delivery tracking.
 */
class SmsSender(
    private val simSlots: SimSlots,
    private val transport: SmsTransport,
    private val defaultSubId: () -> Int? = {
        runCatching { SmsManager.getDefaultSmsSubscriptionId() }
            .getOrNull()?.takeIf { it >= 0 }
    },
) {
    sealed class Result {
        object Sent : Result()
        data class Failed(val reason: String) : Result()
    }

    fun send(destination: String, text: String, slot: Int? = null): Result {
        val cleanDest = destination.trim()
        if (cleanDest.isEmpty()) return Result.Failed("empty destination")
        if (text.isEmpty()) return Result.Failed("empty text")
        val subId = when {
            slot != null -> simSlots.active().firstOrNull { it.slot == slot }?.subId
                ?: return Result.Failed("no active SIM in slot $slot")
            else -> defaultSubId()
        }
        return transport.send(cleanDest, text, subId)
    }
}

/** Seam for [SmsSender]: the real Android SMS stack, or a fake in tests. */
interface SmsTransport {
    fun send(destination: String, text: String, subId: Int?): SmsSender.Result
}

/** [SmsTransport] over the real [SmsManager]; long texts are split with divideMessage. */
class AndroidSmsTransport(context: Context) : SmsTransport {
    private val appContext = context.applicationContext

    override fun send(destination: String, text: String, subId: Int?): SmsSender.Result =
        runCatching {
            val base = appContext.getSystemService(SmsManager::class.java)
                ?: return SmsSender.Result.Failed("no telephony service")
            val mgr = if (subId != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base.createForSubscriptionId(subId)
                else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subId)
            } else base
            val parts = mgr.divideMessage(text)
            if (parts.size <= 1) mgr.sendTextMessage(destination, null, text, null, null)
            else mgr.sendMultipartTextMessage(destination, null, parts, null, null)
            SmsSender.Result.Sent
        }.getOrElse { e -> SmsSender.Result.Failed(e.message ?: e.javaClass.simpleName) }
}
