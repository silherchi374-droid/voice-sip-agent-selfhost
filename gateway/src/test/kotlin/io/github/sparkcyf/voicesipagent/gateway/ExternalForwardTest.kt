package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * External forward (feature 13): an incoming GSM call is forwarded as an outbound SIP INVITE
 * to a configured external URI (e.g. the user's Linphone account) instead of ringing the local
 * phone, so Linphone's servers can push the iPhone. If the external leg fails, the call falls
 * back to the local phone; the missed-call flow still applies if nobody answers.
 */
class ExternalForwardTest {
    private val cell = FakeEndpoint("cellular")
    private val sip = FakeEndpoint("sip")
    private lateinit var bridge: CallBridge
    private val forwardEvents = mutableListOf<Pair<String, String>>()

    private fun build(
        forwardTarget: String? = "sip:alice@sip.linphone.org",
        localTarget: String? = "101",
        answerTimeoutMs: Long = 45_000,
    ) {
        bridge = CallBridge(
            sideA = cell,
            sideB = sip,
            routeAtoB = { localTarget },
            routeBtoA = { it.target },
            config = GatewayConfig(cellularInboundTarget = localTarget ?: "", answerTimeoutMs = answerTimeoutMs),
            externalForwardForA = { leg -> if (leg.kind == "cellular") forwardTarget else null },
            onExternalForward = { t, e -> forwardEvents += t to e },
        )
        bridge.start()
    }

    @Before fun setUp() = build()
    @After fun tearDown() = bridge.stop()

    /** Waits for the bridge thread to drain, including tasks posted by the tasks it ran. */
    private fun settle() = repeat(3) { bridge.drain() }

    @Test
    fun cellularInboundForwardsToExternalInsteadOfLocal() {
        val a = cell.incoming("13800001234")
        settle()
        assertEquals(1, sip.externalDialed.size)
        assertEquals("sip:alice@sip.linphone.org", sip.externalDialed.single().target)
        assertTrue("local phone must not be dialed while forwarding", sip.dialed.isEmpty())
        assertEquals(LegState.RINGING, a.state) // outbound proceeding -> cellular rings
        assertEquals(listOf("sip:alice@sip.linphone.org" to "attempt"), forwardEvents)
    }

    @Test
    fun externalLegAnswersConnectsCallAndBridgesMedia() {
        val a = cell.incoming("13800001234")
        settle()
        val ext = sip.externalDialed.single()
        ext.remoteRinging(); settle()
        assertTrue("cellular must ring while the external leg rings", "ring" in a.actions)
        assertEquals(listOf("sip:alice@sip.linphone.org" to "attempt", "sip:alice@sip.linphone.org" to "ringing"), forwardEvents)
        ext.remoteAnswered(); settle()
        assertEquals(LegState.CONNECTED, a.state)
        assertTrue("answered event must be reported", forwardEvents.contains("sip:alice@sip.linphone.org" to "answered"))

        val cellPort = FakePort(isClockSource = true, fill = 11)
        val extPort = FakePort(isClockSource = false, fill = 22)
        a.mediaUp(cellPort); ext.mediaUp(extPort); settle()
        assertNotNull(bridge.session?.media)
        cellPort.clock!!.onFrame()
        assertEquals(11.toShort(), extPort.written.single()[0])
        assertEquals(22.toShort(), cellPort.written.single()[0])

        ext.remoteEnded(HangupCause.NORMAL); settle()
        assertEquals(LegState.ENDED, a.state)
        assertNull(bridge.session)
    }

    @Test
    fun externalLegFailureFallsBackToLocal() {
        val a = cell.incoming("13800001234")
        settle()
        val ext = sip.externalDialed.single()
        ext.remoteEnded(HangupCause.BUSY); settle()
        // The cellular caller is still waiting (ringing); the local phone is dialed now.
        assertEquals(LegState.RINGING, a.state)
        assertEquals(1, sip.dialed.size)
        assertEquals("101", sip.dialed.single().target)
        assertTrue("failure must be reported", forwardEvents.contains("sip:alice@sip.linphone.org" to "failed:BUSY"))
        assertTrue("fallback must be reported", forwardEvents.contains("sip:alice@sip.linphone.org" to "fallback"))
        assertTrue("session must be marked as fallen back", bridge.session!!.fellBackToLocal)

        val local = sip.dialed.single()
        local.remoteAnswered(); settle()
        assertEquals(LegState.CONNECTED, a.state)
        local.remoteEnded(HangupCause.NORMAL); settle()
        assertNull(bridge.session)
    }

    @Test
    fun externalLegFailureWithNoLocalRouteEndsCall() {
        bridge.stop(); build(localTarget = null)
        val a = cell.incoming("13800001234")
        settle()
        assertEquals(1, sip.externalDialed.size)
        sip.externalDialed.single().remoteEnded(HangupCause.UNAVAILABLE); settle()
        assertEquals(HangupCause.UNAVAILABLE, a.endCause)
        assertNull(bridge.session)
    }

    @Test
    fun externalLegTimeoutFallsBackToLocal() {
        bridge.stop(); build(answerTimeoutMs = 50)
        val a = cell.incoming("13800001234")
        Thread.sleep(150); settle()
        // The external leg timed out at ~50 ms, the local phone was dialed, and it also got no
        // answer within its window — the call ends as no-answer (missed-call flow applies).
        assertEquals("local phone must be dialed after the external leg times out", 1, sip.dialed.size)
        assertTrue(forwardEvents.contains("sip:alice@sip.linphone.org" to "fallback"))
        assertEquals(LegState.ENDED, a.state)
        assertEquals(HangupCause.NO_ANSWER, a.endCause)
        assertNull(bridge.session)
    }

    @Test
    fun localLegFailureAfterFallbackEndsCall() {
        val a = cell.incoming("13800001234")
        settle()
        sip.externalDialed.single().remoteEnded(HangupCause.BUSY); settle()
        // The fallback leg also fails: no second fallback, the call ends with its cause.
        sip.dialed.single().remoteEnded(HangupCause.NO_ANSWER); settle()
        assertEquals(HangupCause.NO_ANSWER, a.endCause)
        assertNull(bridge.session)
    }

    @Test
    fun callerHangsUpDuringExternalAttemptEndsSession() {
        val a = cell.incoming("13800001234")
        settle()
        a.remoteEnded(HangupCause.NORMAL); settle()
        assertEquals("hangup:CANCELLED", sip.externalDialed.single().actions.last())
        assertTrue("no fallback after the caller hung up", sip.dialed.isEmpty())
        assertNull(bridge.session)
    }

    @Test
    fun sipSideInboundNeverForwards() {
        sip.incoming("101", target = "13800005678")
        settle()
        assertEquals(1, cell.dialed.size)
        assertTrue("outgoing calls from the iPhone never use the forward", sip.externalDialed.isEmpty())
        assertTrue(forwardEvents.isEmpty())
    }

    @Test
    fun forwardOffUsesLocalDial() {
        bridge.stop(); build(forwardTarget = null)
        cell.incoming("13800001234")
        settle()
        assertEquals(1, sip.dialed.size)
        assertEquals("101", sip.dialed.single().target)
        assertTrue(sip.externalDialed.isEmpty())
        assertTrue(forwardEvents.isEmpty())
    }

    @Test
    fun forwardWidebandMediaBridges() {
        val a = cell.incoming("13800001234"); settle()
        val ext = sip.externalDialed.single()
        ext.remoteAnswered(); settle()
        val cellPort = FakePort(true, 11, PcmFormat.WIDEBAND)
        val extPort = FakePort(false, 22, PcmFormat.WIDEBAND)
        a.mediaUp(cellPort); ext.mediaUp(extPort); settle()
        cellPort.clock!!.onFrame()
        assertEquals(320, cellPort.written.single().size)
        assertEquals(320, extPort.written.single().size)
        assertTrue(extPort.written.single().all { it == 11.toShort() })
    }
}
