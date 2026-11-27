package io.github.sparkcyf.voicesipagent.gateway

/**
 * Pure call-screening policy (blocklist, night mode, hidden numbers). No Android APIs, so it is
 * unit-testable on the JVM. The [io.github.sparkcyf.voicesipagent.app.GatewayRuntime] supplies
 * the settings; the [CallBridge] calls the veto before dialing the other side.
 */
object CallPolicy {
    /** Normalizes a phone number to its digits (a leading '+' is dropped) for comparison. */
    fun normalizeNumber(number: String?): String? {
        val digits = number?.filter { it.isDigit() }.orEmpty()
        return digits.ifEmpty { null }
    }

    /**
     * True when [number] matches any entry of [blocked]. Matching is by suffix on at least 7
     * digits, so "+216 29 154 710", "0021629154710" and "29154710" all match each other, while a
     * short entry (e.g. "29") can never block a whole number.
     */
    fun isBlocked(number: String?, blocked: Set<String>): Boolean {
        val digits = normalizeNumber(number) ?: return false
        return blocked.any { entry ->
            val e = normalizeNumber(entry) ?: return@any false
            if (minOf(digits.length, e.length) < 7) return@any false
            digits.endsWith(e) || e.endsWith(digits)
        }
    }

    /** True when the caller ID is absent (private/hidden number). */
    fun isHidden(number: String?): Boolean = normalizeNumber(number) == null

    /**
     * True when minute-of-day [nowMin] falls inside [startMin, endMin). Overnight windows
     * (start > end, e.g. 22:00–07:00) wrap past midnight; start == end means "never".
     */
    fun isNightNow(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        val now = nowMin.mod(24 * 60)
        val start = startMin.mod(24 * 60)
        val end = endMin.mod(24 * 60)
        if (start == end) return false
        return if (start < end) now in start until end else now >= start || now < end
    }
}
