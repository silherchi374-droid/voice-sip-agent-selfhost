package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.*
import org.junit.Test
import java.net.BindException
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SipShutdownTest {
    @Test fun unregisterTimeoutStillClosesSocketAndCancelsLongTransactionTimers() {
        DatagramSocket(0).use { peer ->
            val log = object : SipLog {
                override fun info(msg: String) {}
                override fun warn(msg: String, t: Throwable?) {}
            }
            val ua = SipUserAgent(SipConfig(SipAccount("127.0.0.1", peer.localPort, "test", "test"), localPort = 0), log)
            val ready = CountDownLatch(1)
            val thread = AtomicReference<Thread>()
            ua.addStatusListener { _, status ->
                if (status.detail == "registering") { thread.set(Thread.currentThread()); ready.countDown() }
            }
            try {
                ua.start()
                assertTrue(ready.await(2, TimeUnit.SECONDS))
                val port = ua.transport.localPort
                val lateTask = AtomicInteger()
                ua.schedule(60_000) { lateTask.incrementAndGet() }
                // The local peer deliberately never answers either REGISTER.
                ua.stop(timeoutMs = 50)
                thread.get().join(2000)
                assertFalse(thread.get().isAlive)
                assertFalse(ua.status.ready)
                assertEquals("stopped", ua.status.detail)
                assertEquals(0, lateTask.get())
                // The port must become rebindable once stop() closed the socket.
                // Rebinding in one shot can still race the kernel releasing the
                // port (a few ms on a loaded machine); retry briefly. A leaked
                // socket would hold the port for the whole window, so this
                // still proves stop() closed it.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var rebound: DatagramSocket? = null
                var lastError: Exception? = null
                while (rebound == null && System.nanoTime() < deadline) {
                    try {
                        rebound = DatagramSocket(port)
                    } catch (e: BindException) {
                        lastError = e
                        Thread.sleep(10)
                    }
                }
                assertNotNull("port $port still bound 2s after stop: $lastError", rebound)
                rebound!!.use { assertEquals(port, it.localPort) }
            } finally {
                if (thread.get()?.isAlive == true) ua.stop(timeoutMs = 50)
            }
        }
    }
}
