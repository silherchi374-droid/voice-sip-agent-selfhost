package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The subject lines are a contract with the parent agent, which watches the user's Gmail for
 * them — these tests pin the exact formats.
 */
class EmailNotifierTest {
    @Test
    fun missedCallSubjectUsesNumber() {
        assertEquals(
            "[VSA Gateway] Missed call from +21629154710",
            EmailSubjects.missedCall("+21629154710"))
    }

    @Test
    fun missedCallSubjectFallsBackToUnknownNumber() {
        assertEquals("[VSA Gateway] Missed call from unknown number", EmailSubjects.missedCall(null))
        assertEquals("[VSA Gateway] Missed call from unknown number", EmailSubjects.missedCall("  "))
    }

    @Test
    fun smsSubjectUsesSender() {
        assertEquals("[VSA Gateway] SMS from +21620123456", EmailSubjects.sms("+21620123456"))
        assertEquals("[VSA Gateway] SMS from unknown number", EmailSubjects.sms(null))
    }

    @Test
    fun ipChangeAndTestSubjects() {
        assertEquals("[VSA Gateway] New IP: 100.97.107.24", EmailSubjects.newIp("100.97.107.24"))
        assertEquals("[VSA Gateway] Test email", EmailSubjects.TEST)
    }

    @Test
    fun missedCallBodyHasNumberTimeAndSim() {
        val body = EmailBodies.missedCall("+21629154710", 1_794_000_000_000L, 1)
        assertTrue(body.contains("+21629154710"))
        assertTrue(body.contains("SIM 2"))
        assertTrue(body.contains("2026-"))
    }

    @Test
    fun smsBodyHasSenderAndText() {
        val body = EmailBodies.sms("+21620123456", 1_794_000_000_000L, 0, "Your code is 123456")
        assertTrue(body.contains("+21620123456"))
        assertTrue(body.contains("Your code is 123456"))
        assertTrue(body.contains("SIM 1"))
    }

    @Test
    fun configFromRequiresHostUserAndPassword() {
        val base = GatewaySettings()
        assertNull(EmailNotifier.configFrom(base))
        assertNull(EmailNotifier.configFrom(base.copy(smtpHost = "smtp.gmail.com", smtpUser = "a@gmail.com")))
        val config = EmailNotifier.configFrom(
            base.copy(smtpHost = "smtp.gmail.com", smtpUser = "a@gmail.com", smtpPassword = "secret"))
        assertEquals("a@gmail.com", config!!.recipient) // blank recipient defaults to the sender
        assertEquals("starttls", config.security)
        val ssl = EmailNotifier.configFrom(
            base.copy(smtpHost = "smtp.gmail.com", smtpPort = 465, smtpSecurity = "ssl",
                smtpUser = "a@gmail.com", smtpPassword = "secret", emailRecipient = "b@gmail.com"))
        assertEquals("ssl", ssl!!.security)
        assertEquals("b@gmail.com", ssl.recipient)
        assertEquals(465, ssl.port)
    }

    @Test
    fun sendReportsFailureInsteadOfThrowing() {
        // Injected failing sender: the gateway must never crash on a mail failure.
        val notifier = EmailNotifier { _, _, _ -> throw IllegalStateException("smtp down") }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Pair<Boolean, String?>>()
        notifier.send(EmailConfig("smtp.gmail.com", 587, "starttls", "a@gmail.com", "x", "a@gmail.com"),
            EmailSubjects.TEST, EmailBodies.TEST_BODY) { ok, err ->
            result.set(ok to err)
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertFalse(result.get().first)
        assertEquals("smtp down", result.get().second)
    }

    @Test
    fun realJavaMailSendToUnreachableHostFailsGracefully() {
        // .invalid never resolves (RFC 2606), so this exercises the real JavaMail path
        // failing fast instead of hanging or throwing on the caller thread.
        val notifier = EmailNotifier()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Pair<Boolean, String?>>()
        notifier.send(EmailConfig("nonexistent.invalid", 587, "starttls", "a@gmail.com", "x", "a@gmail.com"),
            EmailSubjects.TEST, EmailBodies.TEST_BODY) { ok, err ->
            result.set(ok to err)
            latch.countDown()
        }
        assertTrue("mail send callback never fired", latch.await(30, TimeUnit.SECONDS))
        assertFalse(result.get().first)
        assertTrue(result.get().second?.isNotEmpty() == true)
    }
}

class LocalAddressesTest {
    @Test
    fun classifyCoversKnownInterfaces() {
        assertEquals(AddressKind.VPN_TAILSCALE, LocalAddresses.classify("tailscale0"))
        assertEquals(AddressKind.VPN_ZEROTIER, LocalAddresses.classify("zt7cpc9"))
        assertEquals(AddressKind.MOBILE, LocalAddresses.classify("rmnet_data0"))
        assertEquals(AddressKind.MOBILE, LocalAddresses.classify("ccmni0"))
        assertEquals(AddressKind.WIFI, LocalAddresses.classify("wlan0"))
        assertEquals(AddressKind.WIFI, LocalAddresses.classify("ap0"))
        assertEquals(AddressKind.OTHER, LocalAddresses.classify("dummy0"))
    }

    @Test
    fun primaryPrefersMobileThenAny() {
        val addrs = listOf(
            LocalAddress(AddressKind.WIFI, "Wi-Fi / hotspot", "192.168.43.1"),
            LocalAddress(AddressKind.VPN_TAILSCALE, "Tailscale VPN", "100.64.0.5"),
            LocalAddress(AddressKind.MOBILE, "Mobile data", "100.97.107.24"),
        )
        assertEquals("100.97.107.24", LocalAddresses.primary(addrs))
        assertEquals("192.168.43.1", LocalAddresses.primary(addrs.filter { it.kind != AddressKind.MOBILE }))
        assertNull(LocalAddresses.primary(emptyList()))
    }

    @Test
    fun vpnConnectedDetectsVpnAddresses() {
        assertTrue(LocalAddresses.vpnConnected(
            listOf(LocalAddress(AddressKind.VPN_ZEROTIER, "ZeroTier VPN", "10.0.0.2"))))
        assertFalse(LocalAddresses.vpnConnected(
            listOf(LocalAddress(AddressKind.MOBILE, "Mobile data", "100.97.107.24"))))
    }

    @Test
    fun listDoesNotThrowOnJvm() {
        // Smoke test only: the VM's interfaces vary, but listing must not throw.
        val addrs = LocalAddresses.list()
        assertTrue(addrs.all { it.address.contains('.') })
    }
}
