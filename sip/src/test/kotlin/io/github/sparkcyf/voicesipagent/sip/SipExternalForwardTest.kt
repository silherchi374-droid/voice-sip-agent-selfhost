package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end tests for the external-forward INVITE (feature 13): the gateway (server mode)
 * dials an arbitrary external SIP URI directly, e.g. a Linphone account, instead of the
 * registered phone. Two real [SipUserAgent]s talk over loopback UDP, so the INVITE, SDP
 * answer and dialog teardown are exercised for real.
 *
 * NOTE: these tests need real loopback UDP, which the dev sandbox blocks ("Operation not
 * permitted" on send). They are skipped by hand there and run in CI's jvm-tests job.
 * The malformed-target test needs no network and runs anywhere.
 */
class SipExternalForwardTest {
    private val log = object : SipLog {
        override fun info(msg: String) {}
        override fun warn(msg: String, t: Throwable?) {}
    }

    private fun gatewayUa() = SipUserAgent(
        SipConfig(
            SipAccount("127.0.0.1", 5060, "gateway", ""),
            serverMode = true,
            registrarPassword = "",
            localPort = 0,
        ),
        log,
    )

    /** A plain client-mode UA playing the far side (e.g. Linphone's server + a registered user). */
    private fun farSideUa(gatewayPort: Int, onInvite: (CallLeg) -> Unit) = SipUserAgent(
        SipConfig(SipAccount("127.0.0.1", gatewayPort, "alice", ""), localPort = 0),
        log,
    ).also { it.setIncomingCallHandler { leg -> onInvite(leg) } }

    private fun startServer(server: SipUserAgent) {
        val ready = CountDownLatch(1)
        server.addStatusListener { _, s ->
            if (s.detail == "waiting for phone to register") ready.countDown()
        }
        server.start()
        assertTrue("server did not start", ready.await(5, TimeUnit.SECONDS))
    }

    private fun awaitState(leg: CallLeg, wanted: LegState, timeoutSec: Long = 10): Boolean {
        val end = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < end) {
            if (leg.state == wanted) return true
            if (leg.state == LegState.ENDED && wanted != LegState.ENDED) return false
            Thread.sleep(50)
        }
        return leg.state == wanted
    }

    @Test
    fun externalInviteConnects() {
        val gateway = gatewayUa()
        val farLeg = AtomicReference<CallLeg>()
        val farSawInvite = CountDownLatch(1)
        startServer(gateway)
        val far = farSideUa(gateway.localPort) { leg ->
            farLeg.set(leg)
            leg.answer()
            farSawInvite.countDown()
        }
        try {
            far.start()
            Thread.sleep(500) // let the far side's transport come up
            val out = gateway.dialExternal("sip:alice@127.0.0.1:${far.localPort}", Party("21629154710", "Saif"))
            assertTrue("external leg did not connect", awaitState(out, LegState.CONNECTED))
            assertTrue("far side never saw the INVITE", farSawInvite.await(5, TimeUnit.SECONDS))
            val inbound = farLeg.get()
            assertNotNull("far side never saw the INVITE", inbound)
            assertEquals("alice", inbound!!.target)
            assertEquals("21629154710", inbound.remote.number)
            assertEquals("Saif", inbound.remote.displayName)
            assertEquals(LegState.CONNECTED, inbound.state)
            out.hangup(HangupCause.NORMAL)
            assertTrue("far side did not end", awaitState(inbound, LegState.ENDED))
        } finally {
            far.stop()
            gateway.stop()
        }
    }

    @Test
    fun externalInviteBusyFailsWithBusyCause() {
        val gateway = gatewayUa()
        startServer(gateway)
        val far = farSideUa(gateway.localPort) { leg -> leg.hangup(HangupCause.BUSY) }
        try {
            far.start()
            Thread.sleep(500)
            val out = gateway.dialExternal("sip:alice@127.0.0.1:${far.localPort}", Party("21629154710"))
            assertTrue("external leg did not end", awaitState(out, LegState.ENDED))
            assertEquals(HangupCause.BUSY, out.endCause)
        } finally {
            far.stop()
            gateway.stop()
        }
    }

    @Test
    fun externalInviteChallengedFailsForbidden() {
        // The far side is a server-mode registrar: it challenges the INVITE. The gateway holds
        // no credentials for that domain, so the leg must fail (FORBIDDEN) instead of retrying
        // with an empty digest.
        val gateway = gatewayUa()
        startServer(gateway)
        val challenger = SipUserAgent(
            SipConfig(
                SipAccount("127.0.0.1", 5060, "challenger", ""),
                serverMode = true,
                registrarPassword = "some-secret-123456",
                localPort = 0,
            ),
            log,
        )
        try {
            startServer(challenger)
            val out = gateway.dialExternal("sip:alice@127.0.0.1:${challenger.localPort}", Party("21629154710"))
            assertTrue("external leg did not end", awaitState(out, LegState.ENDED))
            assertEquals(HangupCause.FORBIDDEN, out.endCause)
        } finally {
            challenger.stop()
            gateway.stop()
        }
    }

    @Test
    fun malformedTargetFailsImmediately() {
        val gateway = gatewayUa()
        try {
            gateway.start()
            val out = gateway.dialExternal("not-a-sip-uri", Party("21629154710"))
            assertTrue("malformed target must fail at once", awaitState(out, LegState.ENDED, 5))
            assertEquals(HangupCause.UNAVAILABLE, out.endCause)
            val out2 = gateway.dialExternal("sip:", Party("21629154710"))
            assertTrue("empty-host target must fail at once", awaitState(out2, LegState.ENDED, 5))
            assertEquals(HangupCause.UNAVAILABLE, out2.endCause)
        } finally {
            gateway.stop()
        }
    }
}
