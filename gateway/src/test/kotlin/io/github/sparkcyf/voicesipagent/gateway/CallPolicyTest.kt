package io.github.sparkcyf.voicesipagent.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPolicyTest {
    @Test
    fun blocklistMatchesAcrossFormats() {
        val blocked = setOf("+216 29 154 710")
        assertTrue(CallPolicy.isBlocked("+21629154710", blocked))
        assertTrue(CallPolicy.isBlocked("0021629154710", blocked))
        assertTrue(CallPolicy.isBlocked("29154710", blocked))
        assertTrue(CallPolicy.isBlocked("29 154 710", blocked))
        assertFalse(CallPolicy.isBlocked("+21629154711", blocked))
        assertFalse(CallPolicy.isBlocked(null, blocked))
        assertFalse(CallPolicy.isBlocked("", blocked))
    }

    @Test
    fun shortBlocklistEntriesNeverMatch() {
        // A 2-digit entry must not block every number ending in it.
        assertFalse(CallPolicy.isBlocked("29154710", setOf("10")))
        assertFalse(CallPolicy.isBlocked("29154710", setOf("710")))
        assertTrue(CallPolicy.isBlocked("29154710", setOf("9154710")))
    }

    @Test
    fun hiddenNumberDetection() {
        assertTrue(CallPolicy.isHidden(null))
        assertTrue(CallPolicy.isHidden(""))
        assertTrue(CallPolicy.isHidden("   "))
        assertFalse(CallPolicy.isHidden("29154710"))
        assertFalse(CallPolicy.isHidden("+216 29 154 710"))
    }

    @Test
    fun nightWindowDaytime() {
        assertTrue(CallPolicy.isNightNow(23 * 60, 22 * 60, 7 * 60))
        assertTrue(CallPolicy.isNightNow(0, 22 * 60, 7 * 60))
        assertTrue(CallPolicy.isNightNow(6 * 60 + 59, 22 * 60, 7 * 60))
        assertFalse(CallPolicy.isNightNow(7 * 60, 22 * 60, 7 * 60))
        assertFalse(CallPolicy.isNightNow(12 * 60, 22 * 60, 7 * 60))
        assertFalse(CallPolicy.isNightNow(21 * 60 + 59, 22 * 60, 7 * 60))
    }

    @Test
    fun nightWindowSameDay() {
        assertTrue(CallPolicy.isNightNow(1 * 60, 1 * 60, 5 * 60))
        assertFalse(CallPolicy.isNightNow(5 * 60, 1 * 60, 5 * 60))
        assertFalse(CallPolicy.isNightNow(0, 1 * 60, 5 * 60))
    }

    @Test
    fun nightWindowDegenerateIsNever() {
        assertFalse(CallPolicy.isNightNow(12 * 60, 12 * 60, 12 * 60))
    }
}
