package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end tests for remote commands over SIP MESSAGE (features 1, 2, 8, 11): the registered
 * phone may command the gateway; anyone else must not. Two real [SipUserAgent]s talk over
 * loopback UDP, so the digest handshake and the registered-sender check are exercised for real.
 *
 * NOTE: these tests need real loopback UDP, which the dev sandbox blocks ("Operation not
 * permitted" on send). They are skipped by hand there and run in CI's jvm-tests job.
 */
class SipCommandMessageTest {
    private val log = object : SipLog {
        override fun info(msg: String) {}
        override fun warn(msg: String, t: Throwable?) {}
    }

    private fun serverUa() = SipUserAgent(
        SipConfig(
            SipAccount("127.0.0.1", 5060, "gateway", ""),
            serverMode = true,
            registrarPassword = "test-secret-123456",
            localPort = 0,
        ),
        log,
    )

    private fun clientUa(serverPort: Int, user: String, password: String) = SipUserAgent(
        SipConfig(SipAccount("127.0.0.1", serverPort, user, password), localPort = 0),
        log,
    )

    private fun awaitRegistered(ua: SipUserAgent): Boolean {
        val ready = CountDownLatch(1)
        ua.addStatusListener { _, s -> if (s.ready) ready.countDown() }
        ua.start()
        return ready.await(5, TimeUnit.SECONDS)
    }

    private fun sendCommand(client: SipUserAgent, serverPort: Int, body: String): Int? {
        val code = AtomicReference<Int?>()
        val replied = CountDownLatch(1)
        client.sendMessageTo("sip:gateway@127.0.0.1:$serverPort", body) { c ->
            code.set(c)
            replied.countDown()
        }
        assertTrue("no MESSAGE reply", replied.await(5, TimeUnit.SECONDS))
        return code.get()
    }

    private fun startServer(server: SipUserAgent) {
        val ready = CountDownLatch(1)
        server.addStatusListener { _, s ->
            if (s.detail == "waiting for phone to register") ready.countDown()
        }
        server.start()
        assertTrue("server did not start", ready.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun registeredPhoneCommandIsDelivered() {
        val server = serverUa()
        val received = AtomicReference<String>()
        val delivered = CountDownLatch(1)
        server.onSipMessage = { _, body -> received.set(body); delivered.countDown() }
        startServer(server)
        val client = clientUa(server.localPort, "101", "test-secret-123456")
        try {
            assertTrue("client did not register", awaitRegistered(client))
            assertEquals(200, sendCommand(client, server.localPort, "SMS +21629154710 hello"))
            assertTrue("command not delivered", delivered.await(5, TimeUnit.SECONDS))
            assertEquals("SMS +21629154710 hello", received.get())
        } finally {
            client.stop()
            server.stop()
        }
    }

    @Test
    fun staleRegistrationCannotCommand() {
        // The registrar keeps one phone: the last REGISTER wins. A previously registered
        // phone (still holding the right password) must no longer be able to command us.
        val server = serverUa()
        val delivered = AtomicBoolean(false)
        server.onSipMessage = { _, _ -> delivered.set(true) }
        startServer(server)
        val phone101 = clientUa(server.localPort, "101", "test-secret-123456")
        val phone102 = clientUa(server.localPort, "102", "test-secret-123456")
        try {
            assertTrue("101 did not register", awaitRegistered(phone101))
            assertTrue("102 did not register", awaitRegistered(phone102))
            assertEquals(403, sendCommand(phone101, server.localPort, "RESTART"))
            assertFalse("stale phone's command was delivered", delivered.get())
            assertEquals(200, sendCommand(phone102, server.localPort, "RESTART"))
        } finally {
            phone101.stop()
            phone102.stop()
            server.stop()
        }
    }

    @Test
    fun wrongPasswordGetsNoCommandThrough() {
        val server = serverUa()
        val delivered = AtomicBoolean(false)
        server.onSipMessage = { _, _ -> delivered.set(true) }
        startServer(server)
        val impostor = clientUa(server.localPort, "101", "wrong-password")
        try {
            // start() attempts (and fails) registration; the MESSAGE must still be refused.
            impostor.start()
            Thread.sleep(500)
            val code = sendCommand(impostor, server.localPort, "RESTART")
            assertTrue("wrong password must not get 200, got $code", code != 200)
            assertFalse("impostor command was delivered", delivered.get())
        } finally {
            impostor.stop()
            server.stop()
        }
    }
}
