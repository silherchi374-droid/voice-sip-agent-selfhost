package io.github.sparkcyf.voicesipagent.app

/** Which side started the call. */
enum class CallDirection { INCOMING, OUTGOING }

/** How a bridged call ended. MISSED is incoming-only: the GSM caller was never answered. */
enum class CallOutcome { ANSWERED, MISSED, NO_ANSWER }

/** One row of the in-app call log. Durations are whole seconds; null when never answered. */
data class CallLogEntry(
    val direction: CallDirection,
    val number: String?,
    val timeMs: Long,
    val slot: Int?,
    val durationSec: Long?,
    val outcome: CallOutcome,
)

/** A missed incoming call waiting to be told to the user (SIP MESSAGE on re-register + email). */
data class MissedCall(
    val number: String?,
    val timeMs: Long,
    val slot: Int?,
)

private const val MAX_LOG_ENTRIES = 200
private const val MAX_MISSED = 50

/**
 * Line encoding (no JSON dependency, so the stores stay unit-testable on the JVM):
 * fields are separated by U+001F, lines by '\n'. Phone numbers never contain those, and the
 * numeric/enum fields are validated on read — a corrupt line is skipped, never fatal.
 */
private const val SEP = '\u001F'

private fun CallLogEntry.encode(): String = listOf(
    direction.name,
    number.orEmpty(),
    timeMs.toString(),
    slot?.toString().orEmpty(),
    durationSec?.toString().orEmpty(),
    outcome.name,
).joinToString(SEP.toString())

private fun String.decodeLogEntry(): CallLogEntry? = try {
    val p = (split(SEP) + List(6) { "" }).take(6)
    CallLogEntry(
        direction = CallDirection.valueOf(p[0]),
        number = p[1].ifEmpty { null },
        timeMs = p[2].toLong(),
        slot = p[3].toIntOrNull(),
        durationSec = p[4].toLongOrNull(),
        outcome = CallOutcome.valueOf(p[5]),
    )
} catch (e: Exception) {
    null
}

private fun MissedCall.encode(): String = listOf(
    number.orEmpty(),
    timeMs.toString(),
    slot?.toString().orEmpty(),
).joinToString(SEP.toString())

private fun String.decodeMissedCall(): MissedCall? = try {
    val p = (split(SEP) + List(3) { "" }).take(3)
    MissedCall(
        number = p[0].ifEmpty { null },
        timeMs = p[1].toLong(),
        slot = p[2].toIntOrNull(),
    )
} catch (e: Exception) {
    null
}

/**
 * The in-app call log (feature: call log). Persisted locally on the phone in SharedPreferences,
 * newest first, capped at [MAX_LOG_ENTRIES]. No cloud, no account, nothing leaves the phone.
 */
class CallLogStore(private val store: KeyValueStore) {
    fun load(): List<CallLogEntry> {
        val raw = store.getString(KEY) ?: return emptyList()
        return raw.split('\n').filter { it.isNotEmpty() }.mapNotNull { it.decodeLogEntry() }
    }

    /** Prepends [entry] and returns the new list. */
    fun add(entry: CallLogEntry): List<CallLogEntry> {
        val updated = (listOf(entry) + load()).take(MAX_LOG_ENTRIES)
        save(updated)
        return updated
    }

    fun clear() = save(emptyList())

    private fun save(entries: List<CallLogEntry>) {
        store.putString(KEY, entries.joinToString("\n") { it.encode() })
    }

    companion object {
        internal const val KEY = "call_log_v1"
    }
}

/**
 * Missed calls waiting for delivery to the softphone. A missed call stays queued until the
 * softphone re-registers and the gateway delivers it as a SIP MESSAGE; the queue survives
 * restarts so a missed call is not lost when the gateway itself restarts.
 */
class MissedCallStore(private val store: KeyValueStore) {
    fun load(): List<MissedCall> {
        val raw = store.getString(KEY) ?: return emptyList()
        return raw.split('\n').filter { it.isNotEmpty() }.mapNotNull { it.decodeMissedCall() }
    }

    fun add(missed: MissedCall) {
        save((load() + missed).takeLast(MAX_MISSED))
    }

    /** Drops the first [count] entries (those already delivered). */
    fun drop(count: Int) {
        if (count <= 0) return
        save(load().drop(count))
    }

    fun clear() = save(emptyList())

    private fun save(missed: List<MissedCall>) {
        store.putString(KEY, missed.joinToString("\n") { it.encode() })
    }

    companion object {
        internal const val KEY = "missed_calls_v1"
    }
}
