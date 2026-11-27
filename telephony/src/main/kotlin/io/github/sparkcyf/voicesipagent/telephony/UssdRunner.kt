package io.github.sparkcyf.voicesipagent.telephony

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a USSD code on the gateway SIM (feature 2: balance check from the iPhone; feature 9:
 * automatic daily balance check). [TelephonyManager.sendUssdRequest] is asynchronous — the
 * result arrives on [TelephonyManager.UssdResponseCallback] — so [UssdTransport.request] reports
 * through [onResult] and never blocks.
 *
 * Honest limits: USSD needs CALL_PHONE and, on most Android versions, the app as the default
 * dialer; otherwise the callback reports failure (never a crash). A code that never answers is
 * reported as a timeout after [timeoutMs]. The response text is the carrier's own wording —
 * [UssdBalanceParser] extracts an amount from it best-effort.
 */
class UssdRunner(
    private val transport: UssdTransport,
    private val timeoutMs: Long = 30_000,
) {
    sealed class Result {
        data class Success(val response: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun run(code: String, subId: Int? = null, onResult: (Result) -> Unit) {
        val clean = code.trim()
        if (clean.isEmpty()) {
            onResult(Result.Failed("empty USSD code"))
            return
        }
        transport.request(clean, subId, timeoutMs, onResult)
    }
}

/** Seam for [UssdRunner]: the real Android USSD stack, or a fake in tests. */
interface UssdTransport {
    fun request(code: String, subId: Int?, timeoutMs: Long, onResult: (UssdRunner.Result) -> Unit)
}

/** [UssdTransport] over the real [TelephonyManager]. */
class AndroidUssdTransport(context: Context) : UssdTransport {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun request(code: String, subId: Int?, timeoutMs: Long, onResult: (UssdRunner.Result) -> Unit) {
        val done = AtomicBoolean(false)
        fun finish(result: UssdRunner.Result) {
            if (done.compareAndSet(false, true)) onResult(result)
        }
        val mgr = telephony ?: run {
            finish(UssdRunner.Result.Failed("no telephony service"))
            return
        }
        val target = if (subId != null) runCatching { mgr.createForSubscriptionId(subId) }.getOrNull() ?: mgr else mgr
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(tm: TelephonyManager, request: String, response: CharSequence) {
                finish(UssdRunner.Result.Success(response.toString()))
            }

            override fun onReceiveUssdResponseFailed(tm: TelephonyManager, request: String, failureCode: Int) {
                finish(UssdRunner.Result.Failed("USSD failed (code $failureCode)"))
            }
        }
        mainHandler.postDelayed({ finish(UssdRunner.Result.Failed("USSD timed out after ${timeoutMs / 1000}s")) }, timeoutMs)
        try {
            target.sendUssdRequest(code, callback, mainHandler)
        } catch (e: SecurityException) {
            finish(UssdRunner.Result.Failed("USSD not allowed: ${e.message ?: "needs default dialer / CALL_PHONE"}"))
        } catch (e: Exception) {
            finish(UssdRunner.Result.Failed(e.message ?: e.javaClass.simpleName))
        }
    }
}

/**
 * Best-effort extraction of a balance amount (in TND) from a carrier USSD response, e.g.
 * Ooredoo's *124# reply ("Votre solde est de 12.500 DT", "Solde: 3,250 DT"). The amount must
 * be followed by a currency unit (DT / TND / دينار) so a phone number or a date inside the
 * message is never mistaken for the balance. Returns null when no amount is found — the
 * caller logs it and does NOT alert (never spam on an unparsed reply).
 *
 * Number formats: Tunisian replies use "." or "," as the decimal separator and a space or "."
 * as a thousands separator; both are normalized (e.g. "1 250,500" -> 1250.5, "12.500" -> 12.5).
 */
object UssdBalanceParser {
    private val AMOUNT = Regex("""(\d[\d\s.,]*\d|\d)\s*(DT|TND|دينار|د\.ت\.?|Dinar)""", RegexOption.IGNORE_CASE)

    fun parseAmountTnd(response: String): Double? {
        val raw = AMOUNT.find(response)?.groupValues?.get(1) ?: return null
        val digits = raw.replace(" ", "").replace("\u00A0", "")
        val normalized = when {
            '.' in digits && ',' in digits -> digits.replace(".", "").replace(',', '.')
            ',' in digits -> digits.replace(',', '.')
            else -> digits
        }
        return normalized.toDoubleOrNull()
    }
}
