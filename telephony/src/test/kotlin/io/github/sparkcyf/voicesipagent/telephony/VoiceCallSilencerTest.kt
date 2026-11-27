package io.github.sparkcyf.voicesipagent.telephony

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallSilencerTest {
    private class FakeVolume(var level: Int) : VoiceCallSilencer.VolumeControl {
        val sets = ArrayList<Int>()
        override fun get(): Int = level
        override fun set(value: Int) { level = value; sets += value }
    }

    @Test fun acquireDropsVolumeAndReleaseRestoresIt() {
        val v = FakeVolume(5)
        val s = VoiceCallSilencer(v)
        s.acquire()
        assertEquals(0, v.level)
        assertEquals(listOf(0), v.sets)
        s.release()
        assertEquals(5, v.level)
        assertEquals(listOf(0, 5), v.sets)
    }

    @Test fun refCountedAcrossTwoCalls() {
        val v = FakeVolume(3)
        val s = VoiceCallSilencer(v)
        s.acquire(); s.acquire()
        assertEquals(0, v.level)
        s.release()
        assertEquals("still silenced while one call remains", 0, v.level)
        s.release()
        assertEquals(3, v.level)
    }

    @Test fun userRaisedVolumeMidCallIsRespected() {
        val v = FakeVolume(4)
        val s = VoiceCallSilencer(v)
        s.acquire()
        v.level = 2 // the user raised the volume by hand during the call
        s.release()
        assertEquals("do not overwrite the user's choice", 2, v.level)
    }

    @Test fun releaseWithoutAcquireIsSafe() {
        val v = FakeVolume(6)
        val s = VoiceCallSilencer(v)
        s.release()
        assertEquals(6, v.level)
        assertEquals(emptyList<Int>(), v.sets)
    }

    @Test fun restoreResetsEvenWhenRefCountIsOff() {
        val v = FakeVolume(7)
        val s = VoiceCallSilencer(v)
        s.acquire()
        s.restore()
        assertEquals(7, v.level)
        s.release() // must not restore twice
        assertEquals(7, v.level)
        assertEquals(listOf(0, 7), v.sets)
    }
}
