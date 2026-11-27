package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRecordsTest {
    @Test
    fun callLogRoundTripKeepsNewestFirst() {
        val store = CallLogStore(InMemoryKeyValueStore())
        val first = CallLogEntry(CallDirection.INCOMING, "+21629154710", 1_000L, 0, 42L, CallOutcome.ANSWERED)
        val second = CallLogEntry(CallDirection.OUTGOING, "+33612345678", 2_000L, 1, null, CallOutcome.NO_ANSWER)
        store.add(first)
        val after = store.add(second)
        assertEquals(listOf(second, first), after)
        assertEquals(listOf(second, first), store.load())
    }

    @Test
    fun callLogPersistsAcrossStoreInstances() {
        val kv = InMemoryKeyValueStore()
        CallLogStore(kv).add(
            CallLogEntry(CallDirection.INCOMING, null, 3_000L, null, null, CallOutcome.MISSED))
        val loaded = CallLogStore(kv).load()
        assertEquals(1, loaded.size)
        assertNull(loaded.single().number)
        assertNull(loaded.single().slot)
        assertEquals(CallOutcome.MISSED, loaded.single().outcome)
    }

    @Test
    fun callLogIsCappedAt200() {
        val store = CallLogStore(InMemoryKeyValueStore())
        repeat(205) { i ->
            store.add(CallLogEntry(CallDirection.INCOMING, "1", i.toLong(), null, null, CallOutcome.MISSED))
        }
        val loaded = store.load()
        assertEquals(200, loaded.size)
        assertEquals(204L, loaded.first().timeMs)
    }

    @Test
    fun callLogSkipsCorruptLines() {
        val backing = InMemoryKeyValueStore()
        CallLogStore(backing).add(
            CallLogEntry(CallDirection.INCOMING, "+123", 5_000L, 0, 10L, CallOutcome.ANSWERED))
        val raw = backing.getString(CallLogStore.KEY)!!
        val kv = InMemoryKeyValueStore()
        kv.putString(CallLogStore.KEY, "garbage\n$raw\nBOGUS\u001F1\u001F2")
        val loaded = CallLogStore(kv).load()
        assertEquals(1, loaded.size)
        assertEquals("+123", loaded.single().number)
    }

    @Test
    fun missedCallQueueDropAndClear() {
        val store = MissedCallStore(InMemoryKeyValueStore())
        store.add(MissedCall("+111", 1_000L, 0))
        store.add(MissedCall("+222", 2_000L, 1))
        store.add(MissedCall(null, 3_000L, null))
        assertEquals(3, store.load().size)
        store.drop(2)
        val remaining = store.load()
        assertEquals(1, remaining.size)
        assertNull(remaining.single().number)
        store.clear()
        assertTrue(store.load().isEmpty())
    }
}
