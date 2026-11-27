package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.PhoneAccountHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSenderTest {
    private class FakeSlots(private val slots: List<SimSlot>) : SimSlots {
        override fun active(): List<SimSlot> = slots
        override fun slotOf(account: PhoneAccountHandle?): Int? = null
        override fun defaultAccount(): PhoneAccountHandle? = null
    }

    private class FakeTransport(var result: SmsSender.Result = SmsSender.Result.Sent) : SmsTransport {
        val sent = ArrayList<Triple<String, String, Int?>>()
        override fun send(destination: String, text: String, subId: Int?): SmsSender.Result {
            sent += Triple(destination, text, subId)
            return result
        }
    }

    private val slots = FakeSlots(listOf(SimSlot(0, 1, "Ooredoo", null), SimSlot(1, 2, "TUNTEL", null)))

    @Test
    fun explicitSlotResolvesToSubId() {
        val t = FakeTransport()
        val sender = SmsSender(slots, t)
        assertEquals(SmsSender.Result.Sent, sender.send("+21629154710", "hello", slot = 1))
        assertEquals(listOf(Triple("+21629154710", "hello", 2)), t.sent)
    }

    @Test
    fun defaultSlotUsesDefaultSubId() {
        val t = FakeTransport()
        val sender = SmsSender(slots, t, defaultSubId = { 1 })
        assertEquals(SmsSender.Result.Sent, sender.send("+21629154710", "hello"))
        assertEquals(listOf(Triple("+21629154710", "hello", 1)), t.sent)
    }

    @Test
    fun missingSlotFailsWithoutSending() {
        val t = FakeTransport()
        val sender = SmsSender(slots, t)
        val result = sender.send("+21629154710", "hello", slot = 5)
        assertTrue(result is SmsSender.Result.Failed)
        assertTrue(t.sent.isEmpty())
    }

    @Test
    fun emptyDestinationOrTextFailsWithoutSending() {
        val t = FakeTransport()
        val sender = SmsSender(slots, t)
        assertTrue(sender.send("  ", "hello") is SmsSender.Result.Failed)
        assertTrue(sender.send("+21629154710", "") is SmsSender.Result.Failed)
        assertTrue(t.sent.isEmpty())
    }

    @Test
    fun transportFailurePropagates() {
        val t = FakeTransport(SmsSender.Result.Failed("no service"))
        val sender = SmsSender(slots, t)
        assertEquals(SmsSender.Result.Failed("no service"), sender.send("+21629154710", "hello"))
    }
}

class UssdRunnerTest {
    private class FakeTransport(var result: UssdRunner.Result) : UssdTransport {
        val requested = ArrayList<Triple<String, Int?, Long>>()
        override fun request(code: String, subId: Int?, timeoutMs: Long, onResult: (UssdRunner.Result) -> Unit) {
            requested += Triple(code, subId, timeoutMs)
            onResult(result)
        }
    }

    @Test
    fun runForwardsCodeAndResult() {
        val t = FakeTransport(UssdRunner.Result.Success("Votre solde est de 12.500 DT"))
        val runner = UssdRunner(t)
        var got: UssdRunner.Result? = null
        runner.run("*124#", subId = 1) { got = it }
        assertEquals(listOf(Triple("*124#", 1, 30_000L)), t.requested)
        assertEquals(UssdRunner.Result.Success("Votre solde est de 12.500 DT"), got)
    }

    @Test
    fun emptyCodeFailsWithoutRequest() {
        val t = FakeTransport(UssdRunner.Result.Success("x"))
        val runner = UssdRunner(t)
        var got: UssdRunner.Result? = null
        runner.run("  ") { got = it }
        assertTrue(t.requested.isEmpty())
        assertTrue(got is UssdRunner.Result.Failed)
    }

    @Test
    fun balanceParserOoredooFormats() {
        assertEquals(12.5, UssdBalanceParser.parseAmountTnd("Votre solde est de 12.500 DT")!!, 0.001)
        assertEquals(3.25, UssdBalanceParser.parseAmountTnd("Solde: 3,250 DT")!!, 0.001)
        assertEquals(1250.5, UssdBalanceParser.parseAmountTnd("Solde 1 250,500 DT")!!, 0.001)
        assertEquals(7.5, UssdBalanceParser.parseAmountTnd("رصيدك 7.5 دينار")!!, 0.001)
        assertEquals(0.0, UssdBalanceParser.parseAmountTnd("Votre solde est de 0 DT")!!, 0.001)
    }

    @Test
    fun balanceParserIgnoresNonAmounts() {
        assertEquals(null, UssdBalanceParser.parseAmountTnd("Solde insuffisant, rechargez"))
        assertEquals(null, UssdBalanceParser.parseAmountTnd("Votre numéro est le 29154710"))
        assertEquals(null, UssdBalanceParser.parseAmountTnd(""))
    }
}
